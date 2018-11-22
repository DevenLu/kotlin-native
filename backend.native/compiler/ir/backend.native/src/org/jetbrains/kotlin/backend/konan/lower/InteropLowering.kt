/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package org.jetbrains.kotlin.backend.konan.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.lower.*
import org.jetbrains.kotlin.backend.common.peek
import org.jetbrains.kotlin.backend.common.pop
import org.jetbrains.kotlin.backend.common.push
import org.jetbrains.kotlin.backend.konan.*
import org.jetbrains.kotlin.backend.konan.getInlinedClass
import org.jetbrains.kotlin.backend.konan.descriptors.allOverriddenDescriptors
import org.jetbrains.kotlin.backend.konan.descriptors.isInterface
import org.jetbrains.kotlin.backend.konan.descriptors.synthesizedName
import org.jetbrains.kotlin.backend.konan.irasdescriptors.*
import org.jetbrains.kotlin.builtins.UnsignedTypes
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.ClassConstructorDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.TypeParameterDescriptor
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.descriptors.annotations.AnnotationDescriptorImpl
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.descriptors.impl.SimpleFunctionDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.ValueParameterDescriptorImpl
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrFunctionImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrValueParameterImpl
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.OverridingUtil
import org.jetbrains.kotlin.resolve.constants.StringValue
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.descriptorUtil.getSuperClassNotAny

internal class InteropLoweringPart1(val context: Context) : IrBuildingTransformer(context), FileLoweringPass {

    private val symbols get() = context.ir.symbols
    private val symbolTable get() = symbols.symbolTable

    lateinit var currentFile: IrFile

    private val topLevelInitializers = mutableListOf<IrExpression>()

    override fun lower(irFile: IrFile) {
        currentFile = irFile
        irFile.transformChildrenVoid(this)

        topLevelInitializers.forEach { irFile.addTopLevelInitializer(it, context, false) }
        topLevelInitializers.clear()
    }

    private fun IrBuilderWithScope.callAlloc(classPtr: IrExpression): IrExpression =
            irCall(symbols.interopAllocObjCObject).apply {
                putValueArgument(0, classPtr)
            }

    private fun IrBuilderWithScope.getObjCClass(classSymbol: IrClassSymbol): IrExpression {
        val classDescriptor = classSymbol.descriptor
        assert(!classDescriptor.isObjCMetaClass())
        return irCall(symbols.interopGetObjCClass, symbols.nativePtrType, listOf(classSymbol.typeWithStarProjections))
    }

    private val outerClasses = mutableListOf<IrClass>()

    override fun visitClass(declaration: IrClass): IrStatement {
        if (declaration.isKotlinObjCClass()) {
            lowerKotlinObjCClass(declaration)
        }

        outerClasses.push(declaration)
        try {
            return super.visitClass(declaration)
        } finally {
            outerClasses.pop()
        }
    }

    private fun lowerKotlinObjCClass(irClass: IrClass) {
        checkKotlinObjCClass(irClass)

        val interop = context.interopBuiltIns

        irClass.declarations.toList().mapNotNull {
            when {
                it is IrSimpleFunction && it.descriptor.annotations.hasAnnotation(interop.objCAction) ->
                        generateActionImp(it)

                it is IrProperty && it.descriptor.annotations.hasAnnotation(interop.objCOutlet) ->
                        generateOutletSetterImp(it)

                it is IrConstructor && it.descriptor.annotations.hasAnnotation(interop.objCOverrideInit) ->
                        generateOverrideInit(irClass, it)

                else -> null
            }
        }.let { irClass.addChildren(it) }

        if (irClass.descriptor.annotations.hasAnnotation(interop.exportObjCClass.fqNameSafe)) {
            val irBuilder = context.createIrBuilder(currentFile.symbol).at(irClass)
            topLevelInitializers.add(irBuilder.getObjCClass(irClass.symbol))
        }
    }

    private fun generateOverrideInit(irClass: IrClass, constructor: IrConstructor): IrSimpleFunction {
        val superClass = irClass.getSuperClassNotAny()!!
        val superConstructors = superClass.constructors.filter {
            constructor.overridesConstructor(it)
        }.toList()

        val superConstructor = superConstructors.singleOrNull() ?: run {
            val annotation = context.interopBuiltIns.objCOverrideInit.name
            if (superConstructors.isEmpty()) {
                context.reportCompilationError(
                        """
                            constructor with @$annotation doesn't override any super class constructor.
                            It must completely match by parameter names and types.""".trimIndent(),
                        currentFile,
                        constructor
                )
            } else {
                context.reportCompilationError(
                        "constructor with @$annotation matches more than one of super constructors",
                        currentFile,
                        constructor
                )
            }
        }

        val initMethod = superConstructor.getObjCInitMethod()!!

        // Remove fake overrides of this init method, also check for explicit overriding:
        irClass.declarations.removeAll {
            if (it is IrSimpleFunction && initMethod.symbol in it.overriddenSymbols) {
                if (it.isReal) {
                    val annotation = context.interopBuiltIns.objCOverrideInit.name
                    context.reportCompilationError(
                            "constructor with @$annotation overrides initializer that is already overridden explicitly",
                            currentFile,
                            constructor
                    )
                }
                true
            } else {
                false
            }
        }

        // Generate `override fun init...(...) = this.initBy(...)`:

        val resultDescriptor = SimpleFunctionDescriptorImpl.create(
                irClass.descriptor,
                Annotations.EMPTY,
                initMethod.name,
                CallableMemberDescriptor.Kind.DECLARATION,
                SourceElement.NO_SOURCE
        )

        val valueParameters = initMethod.valueParameters.map {
            val descriptor = ValueParameterDescriptorImpl(
                    resultDescriptor,
                    null,
                    it.index,
                    Annotations.EMPTY,
                    it.name,
                    it.descriptor.type,
                    false,
                    false,
                    false,
                    it.varargElementType?.toKotlinType(),
                    SourceElement.NO_SOURCE
            )
            it.copy(descriptor)
        }
        resultDescriptor.initialize(
                null,
                irClass.descriptor.thisAsReceiverParameter,
                emptyList<TypeParameterDescriptor>(),
                valueParameters.map { it.descriptor as ValueParameterDescriptor },
                irClass.descriptor.defaultType,
                Modality.OPEN,
                Visibilities.PUBLIC
        )

        return IrFunctionImpl(
                constructor.startOffset, constructor.endOffset, OVERRIDING_INITIALIZER_BY_CONSTRUCTOR,
                resultDescriptor,
                irClass.defaultType
        ).also { result ->
            result.parent = irClass
            result.createDispatchReceiverParameter()
            result.valueParameters += valueParameters

            result.overriddenSymbols.add(initMethod.symbol)
            result.descriptor.overriddenDescriptors = listOf(initMethod.descriptor)

            result.body = context.createIrBuilder(result.symbol).irBlockBody(result) {
                +irReturn(
                        irCall(symbols.interopObjCObjectInitBy, listOf(irClass.defaultType)).apply {
                            extensionReceiver = irGet(result.dispatchReceiverParameter!!)
                            putValueArgument(0, irCall(constructor).also {
                                result.valueParameters.forEach { parameter ->
                                    it.putValueArgument(parameter.index, irGet(parameter))
                                }
                            })
                        }
                )
            }

            assert(result.getObjCMethodInfo() != null) // Ensure it gets correctly recognized by the compiler.
        }
    }

    private object OVERRIDING_INITIALIZER_BY_CONSTRUCTOR :
            IrDeclarationOriginImpl("OVERRIDING_INITIALIZER_BY_CONSTRUCTOR")

    private fun IrConstructor.overridesConstructor(other: IrConstructor): Boolean {
        return this.descriptor.valueParameters.size == other.descriptor.valueParameters.size &&
                this.descriptor.valueParameters.all {
                    val otherParameter = other.descriptor.valueParameters[it.index]
                    it.name == otherParameter.name && it.type == otherParameter.type
                }
    }

    private fun generateActionImp(function: IrSimpleFunction): IrSimpleFunction {
        val action = "@${context.interopBuiltIns.objCAction.name}"

        function.extensionReceiverParameter?.let {
            context.reportCompilationError("$action method must not have extension receiver",
                    currentFile, it)
        }

        function.valueParameters.forEach {
            val kotlinType = it.descriptor.type
            if (!kotlinType.isObjCObjectType()) {
                context.reportCompilationError("Unexpected $action method parameter type: $kotlinType\n" +
                        "Only Objective-C object types are supported here",
                        currentFile, it)
            }
        }

        val returnType = function.returnType

        if (!returnType.isUnit()) {
            context.reportCompilationError("Unexpected $action method return type: ${returnType.toKotlinType()}\n" +
                    "Only 'Unit' is supported here",
                    currentFile, function
            )
        }

        return generateFunctionImp(inferObjCSelector(function.descriptor), function)
    }

    private fun generateOutletSetterImp(property: IrProperty): IrSimpleFunction {
        val descriptor = property.descriptor

        val outlet = "@${context.interopBuiltIns.objCOutlet.name}"

        if (!descriptor.isVar) {
            context.reportCompilationError("$outlet property must be var",
                    currentFile, property)
        }

        property.getter?.extensionReceiverParameter?.let {
            context.reportCompilationError("$outlet must not have extension receiver",
                    currentFile, it)
        }

        val type = descriptor.type
        if (!type.isObjCObjectType()) {
            context.reportCompilationError("Unexpected $outlet type: $type\n" +
                    "Only Objective-C object types are supported here",
                    currentFile, property)
        }

        val name = descriptor.name.asString()
        val selector = "set${name.capitalize()}:"

        return generateFunctionImp(selector, property.setter!!)
    }

    private fun getMethodSignatureEncoding(function: IrFunction): String {
        assert(function.extensionReceiverParameter == null)
        assert(function.valueParameters.all { it.type.isObjCObjectType() })
        assert(function.returnType.isUnit())

        // Note: these values are valid for x86_64 and arm64.
        return when (function.valueParameters.size) {
            0 -> "v16@0:8"
            1 -> "v24@0:8@16"
            2 -> "v32@0:8@16@24"
            else -> context.reportCompilationError("Only 0, 1 or 2 parameters are supported here",
                    currentFile, function
            )
        }
    }

    private fun generateFunctionImp(selector: String, function: IrFunction): IrSimpleFunction {
        val signatureEncoding = getMethodSignatureEncoding(function)

        val returnType = function.returnType
        assert(returnType.isUnit())

        val nativePtrType = context.ir.symbols.nativePtrType

        val parameterTypes = mutableListOf(nativePtrType) // id self

        parameterTypes.add(nativePtrType) // SEL _cmd

        function.valueParameters.mapTo(parameterTypes) {
            when {
                it.descriptor.type.isObjCObjectType() -> nativePtrType
                else -> TODO()
            }
        }

        // Annotations to be detected in KotlinObjCClassInfoGenerator:
        val annotations = createObjCMethodImpAnnotations(selector = selector, encoding = signatureEncoding)

        val newDescriptor = SimpleFunctionDescriptorImpl.create(
                function.descriptor.containingDeclaration,
                annotations,
                ("imp:" + selector).synthesizedName,
                CallableMemberDescriptor.Kind.SYNTHESIZED,
                SourceElement.NO_SOURCE
        )

        val valueParameters = parameterTypes.mapIndexed { index, it ->
            ValueParameterDescriptorImpl(
                    newDescriptor,
                    null,
                    index,
                    Annotations.EMPTY,
                    Name.identifier("p$index"),
                    it.toKotlinType(),
                    false,
                    false,
                    false,
                    null,
                    SourceElement.NO_SOURCE
            )
        }

        newDescriptor.initialize(
                null, null,
                emptyList(),
                valueParameters,
                function.descriptor.returnType,
                Modality.FINAL,
                Visibilities.PRIVATE
        )

        val newFunction = IrFunctionImpl(
                function.startOffset, function.endOffset,
                IrDeclarationOrigin.DEFINED,
                newDescriptor,
                function.returnType
        ).apply {
            parameterTypes.mapIndexedTo(this.valueParameters) { index, it ->
                IrValueParameterImpl(
                        startOffset,
                        endOffset,
                        IrDeclarationOrigin.DEFINED,
                        descriptor.valueParameters[index],
                        it,
                        null
                )
            }
        }

        val builder = context.createIrBuilder(newFunction.symbol)
        newFunction.body = builder.irBlockBody(newFunction) {
            +irCall(function).apply {
                dispatchReceiver = interpretObjCPointer(
                        irGet(newFunction.valueParameters[0]),
                        function.dispatchReceiverParameter!!.type
                )

                function.valueParameters.forEachIndexed { index, parameter ->
                    putValueArgument(index,
                            interpretObjCPointer(
                                    irGet(newFunction.valueParameters[index + 2]),
                                    parameter.type
                            )
                    )
                }
            }
        }

        return newFunction
    }

    private fun IrBuilderWithScope.interpretObjCPointer(expression: IrExpression, type: IrType): IrExpression {
        val callee: IrFunctionSymbol = if (type.containsNull()) {
            symbols.interopInterpretObjCPointerOrNull
        } else {
            symbols.interopInterpretObjCPointer
        }

        return irCall(callee, listOf(type)).apply {
            putValueArgument(0, expression)
        }
    }

    private fun createObjCMethodImpAnnotations(selector: String, encoding: String): Annotations {
        val annotation = AnnotationDescriptorImpl(
                context.interopBuiltIns.objCMethodImp.defaultType,
                mapOf("selector" to selector, "encoding" to encoding)
                        .mapKeys { Name.identifier(it.key) }
                        .mapValues { StringValue(it.value) },
                SourceElement.NO_SOURCE
        )

        return Annotations.create(listOf(annotation))
    }

    private fun checkKotlinObjCClass(irClass: IrClass) {
        val kind = irClass.descriptor.kind
        if (kind != ClassKind.CLASS && kind != ClassKind.OBJECT) {
            context.reportCompilationError(
                    "Only classes are supported as subtypes of Objective-C types",
                    currentFile, irClass
            )
        }

        if (!irClass.descriptor.isFinalClass) {
            context.reportCompilationError(
                    "Non-final Kotlin subclasses of Objective-C classes are not yet supported",
                    currentFile, irClass
            )
        }

        var hasObjCClassSupertype = false
        irClass.descriptor.defaultType.constructor.supertypes.forEach {
            val descriptor = it.constructor.declarationDescriptor as ClassDescriptor
            if (!descriptor.isObjCClass()) {
                context.reportCompilationError(
                        "Mixing Kotlin and Objective-C supertypes is not supported",
                        currentFile, irClass
                )
            }

            if (descriptor.kind == ClassKind.CLASS) {
                hasObjCClassSupertype = true
            }
        }

        if (!hasObjCClassSupertype) {
            context.reportCompilationError(
                    "Kotlin implementation of Objective-C protocol must have Objective-C superclass (e.g. NSObject)",
                    currentFile, irClass
            )
        }

        val methodsOfAny =
                context.ir.symbols.any.owner.declarations.filterIsInstance<IrSimpleFunction>().toSet()

        irClass.declarations.filterIsInstance<IrSimpleFunction>().filter { it.isReal }.forEach { method ->
            val overriddenMethodOfAny = method.allOverriddenDescriptors.firstOrNull {
                it in methodsOfAny
            }

            if (overriddenMethodOfAny != null) {
                val correspondingObjCMethod = when (method.name.asString()) {
                    "toString" -> "'description'"
                    "hashCode" -> "'hash'"
                    "equals" -> "'isEqual:'"
                    else -> "corresponding Objective-C method"
                }

                context.report(
                        method,
                        "can't override '${method.name}', override $correspondingObjCMethod instead",
                        isError = true
                )
            }
        }
    }

    override fun visitDelegatingConstructorCall(expression: IrDelegatingConstructorCall): IrExpression {
        expression.transformChildrenVoid()

        builder.at(expression)

        val constructedClass = outerClasses.peek()!!
        val constructedClassDescriptor = constructedClass.descriptor

        if (!constructedClass.isObjCClass()) {
            return expression
        }

        constructedClass.parent.let { parent ->
            if (parent is IrClass && parent.isObjCClass() &&
                    constructedClass == parent.companionObject()) {

                // Note: it is actually not used; getting values of such objects is handled by code generator
                // in [FunctionGenerationContext.getObjectValue].

                return expression
            }
        }

        if (!constructedClass.isExternalObjCClass() &&
            (expression.symbol.owner.constructedClass).isExternalObjCClass()) {

            // Calling super constructor from Kotlin Objective-C class.

            assert(constructedClassDescriptor.getSuperClassNotAny() == expression.descriptor.constructedClass)

            val initMethod = expression.descriptor.getObjCInitMethod()!!

            if (!expression.symbol.owner.objCConstructorIsDesignated()) {
                context.reportCompilationError(
                        "Unable to call non-designated initializer as super constructor",
                        currentFile,
                        expression
                )
            }

            val initMethodInfo = initMethod.getExternalObjCMethodInfo()!!

            assert(expression.dispatchReceiver == null)
            assert(expression.extensionReceiver == null)

            val initCall = builder.genLoweredObjCMethodCall(
                    initMethodInfo,
                    superQualifier = symbolTable.referenceClass(expression.descriptor.constructedClass),
                    receiver = builder.getRawPtr(builder.irGet(constructedClass.thisReceiver!!)),
                    arguments = initMethod.valueParameters.map { expression.getValueArgument(it)!! }
            )

            val superConstructor = symbolTable.referenceConstructor(
                    expression.descriptor.constructedClass.constructors.single { it.valueParameters.size == 0 }
            )

            return builder.irBlock(expression) {
                // Required for the IR to be valid, will be ignored in codegen:
                +IrDelegatingConstructorCallImpl(
                        startOffset,
                        endOffset,
                        context.irBuiltIns.unitType,
                        superConstructor,
                        superConstructor.descriptor,
                        0
                )

                +irCall(symbols.interopObjCObjectSuperInitCheck).apply {
                    extensionReceiver = irGet(constructedClass.thisReceiver!!)
                    putValueArgument(0, initCall)
                }
            }
        }

        return expression
    }

    private fun IrBuilderWithScope.genLoweredObjCMethodCall(info: ObjCMethodInfo, superQualifier: IrClassSymbol?,
                                         receiver: IrExpression, arguments: List<IrExpression>): IrExpression {

        val superClass = superQualifier?.let { getObjCClass(it) } ?:
                irCall(symbols.getNativeNullPtr, symbols.nativePtrType)

        val bridge = symbolTable.referenceSimpleFunction(info.bridge)
        return irCall(bridge, symbolTable.translateErased(info.bridge.returnType!!)).apply {
            putValueArgument(0, superClass)
            putValueArgument(1, receiver)

            assert(arguments.size + 2 == info.bridge.valueParameters.size)
            arguments.forEachIndexed { index, argument ->
                putValueArgument(index + 2, argument)
            }
        }
    }

    override fun visitCall(expression: IrCall): IrExpression {
        expression.transformChildrenVoid()

        val descriptor = expression.descriptor.original

        if (descriptor is ConstructorDescriptor) {
            val initMethod = descriptor.getObjCInitMethod()

            if (initMethod != null) {
                val arguments = descriptor.valueParameters.map { expression.getValueArgument(it)!! }
                assert(expression.extensionReceiver == null)
                assert(expression.dispatchReceiver == null)

                val constructedClass = descriptor.constructedClass
                val initMethodInfo = initMethod.getExternalObjCMethodInfo()!!
                return builder.at(expression).run {
                    val classPtr = getObjCClass(symbolTable.referenceClass(constructedClass))
                    irForceNotNull(callAllocAndInit(classPtr, initMethodInfo, arguments))
                }
            }
        }

        descriptor.getObjCFactoryInitMethodInfo()?.let { initMethodInfo ->
            val arguments = (0 until expression.valueArgumentsCount)
                    .map { index -> expression.getValueArgument(index)!! }

            return builder.at(expression).run {
                val classPtr = getRawPtr(expression.extensionReceiver!!)
                callAllocAndInit(classPtr, initMethodInfo, arguments)
            }
        }

        descriptor.getExternalObjCMethodInfo()?.let { methodInfo ->
            val isInteropStubsFile =
                    currentFile.fileAnnotations.any { it.fqName ==  FqName("kotlinx.cinterop.InteropStubs") }

            // Special case: bridge from Objective-C method implementation template to Kotlin method;
            // handled in CodeGeneratorVisitor.callVirtual.
            val useKotlinDispatch = isInteropStubsFile &&
                    builder.scope.scopeOwner.annotations.hasAnnotation(FqName("kotlin.native.internal.ExportForCppRuntime"))

            if (!useKotlinDispatch) {
                val arguments = descriptor.valueParameters.map { expression.getValueArgument(it)!! }
                assert(expression.dispatchReceiver == null || expression.extensionReceiver == null)

                if (expression.superQualifier?.isObjCMetaClass() == true) {
                    context.reportCompilationError(
                            "Super calls to Objective-C meta classes are not supported yet",
                            currentFile, expression
                    )
                }

                if (expression.superQualifier?.isInterface == true) {
                    context.reportCompilationError(
                            "Super calls to Objective-C protocols are not allowed",
                            currentFile, expression
                    )
                }

                builder.at(expression)
                return builder.genLoweredObjCMethodCall(
                        methodInfo,
                        superQualifier = expression.superQualifierSymbol,
                        receiver = builder.getRawPtr(expression.dispatchReceiver ?: expression.extensionReceiver!!),
                        arguments = arguments
                )
            }
        }

        return when (descriptor) {
            context.interopBuiltIns.typeOf -> {
                val typeArgument = expression.getSingleTypeArgument()
                val classSymbol = typeArgument.classifierOrNull as? IrClassSymbol

                if (classSymbol == null) {
                    expression
                } else {
                    val classDescriptor = classSymbol.descriptor
                    val companionObject = classDescriptor.companionObjectDescriptor ?:
                            error("native variable class $classDescriptor must have the companion object")

                    builder.at(expression).irGetObject(symbolTable.referenceClass(companionObject))
                }
            }
            else -> expression
        }
    }

    override fun visitProperty(declaration: IrProperty): IrStatement {
        val backingField = declaration.backingField
        return if (declaration.isConst && backingField?.isStatic == true && context.config.isInteropStubs) {
            // Transform top-level `const val x = 42` to `val x get() = 42`.
            // Generally this transformation is just an optimization to ensure that interop constants
            // don't require any storage and/or initialization at program startup.
            // Also it is useful due to uncertain design of top-level stored properties in Kotlin/Native.
            val initializer = backingField.initializer!!.expression
            declaration.backingField = null

            val getter = declaration.getter!!
            val getterBody = getter.body!! as IrBlockBody
            getterBody.statements.clear()
            getterBody.statements += IrReturnImpl(
                    declaration.startOffset,
                    declaration.endOffset,
                    context.irBuiltIns.nothingType,
                    getter.symbol,
                    initializer
            )
            // Note: in interop stubs const val initializer is either `IrConst` or quite simple expression,
            // so it is ok to compute it every time.

            assert(declaration.setter == null)
            assert(!declaration.isVar)

            declaration.transformChildrenVoid()
            declaration
        } else {
            super.visitProperty(declaration)
        }
    }

    private fun IrBuilderWithScope.callAllocAndInit(
            classPtr: IrExpression,
            initMethodInfo: ObjCMethodInfo,
            arguments: List<IrExpression>
    ): IrExpression = irBlock {
        val allocated = irTemporaryVar(callAlloc(classPtr))

        val initCall = genLoweredObjCMethodCall(
                initMethodInfo,
                superQualifier = null,
                receiver = irGet(allocated),
                arguments = arguments
        )

        +IrTryImpl(startOffset, endOffset, initCall.type).apply {
            tryResult = initCall
            finallyExpression = irCall(symbols.interopObjCRelease).apply {
                putValueArgument(0, irGet(allocated)) // Balance pointer retained by alloc.
            }
        }
    }

    private fun IrBuilderWithScope.getRawPtr(receiver: IrExpression) =
            irCall(symbols.interopObjCObjectRawValueGetter).apply {
                extensionReceiver = receiver
            }
}

/**
 * Lowers some interop intrinsic calls.
 */
internal class InteropLoweringPart2(val context: Context) : FileLoweringPass {
    override fun lower(irFile: IrFile) {
        val transformer = InteropTransformer(context, irFile)
        irFile.transformChildrenVoid(transformer)
    }
}

private class InteropTransformer(val context: Context, val irFile: IrFile) : IrBuildingTransformer(context) {

    val interop = context.interopBuiltIns
    val symbols = context.ir.symbols

    override fun visitCall(expression: IrCall): IrExpression {

        expression.transformChildrenVoid(this)
        builder.at(expression)
        val descriptor = expression.descriptor.original
        val function = expression.symbol.owner

        if (function is IrConstructor) {
            val inlinedClass = function.returnType.getInlinedClass()
            if (inlinedClass?.descriptor == interop.cPointer || inlinedClass?.descriptor == interop.nativePointed) {
                throw Error("Native interop types constructors must not be called directly")
            }
        }

        if (descriptor == interop.nativePointedRawPtrGetter ||
                OverridingUtil.overrides(descriptor, interop.nativePointedRawPtrGetter)) {

            // Replace by the intrinsic call to be handled by code generator:
            return builder.irCall(symbols.interopNativePointedGetRawPointer).apply {
                extensionReceiver = expression.dispatchReceiver
            }
        }

        fun reportError(message: String): Nothing = context.reportCompilationError(message, irFile, expression)

        return when (descriptor) {
            interop.cPointerRawValue.getter ->
                // Replace by the intrinsic call to be handled by code generator:
                builder.irCall(symbols.interopCPointerGetRawValue).apply {
                    extensionReceiver = expression.dispatchReceiver
                }

            interop.bitsToFloat -> {
                val argument = expression.getValueArgument(0)
                if (argument is IrConst<*> && argument.kind == IrConstKind.Int) {
                    val floatValue = kotlinx.cinterop.bitsToFloat(argument.value as Int)
                    builder.irFloat(floatValue)
                } else {
                    expression
                }
            }

            interop.bitsToDouble -> {
                val argument = expression.getValueArgument(0)
                if (argument is IrConst<*> && argument.kind == IrConstKind.Long) {
                    val doubleValue = kotlinx.cinterop.bitsToDouble(argument.value as Long)
                    builder.irDouble(doubleValue)
                } else {
                    expression
                }
            }

            in interop.staticCFunction -> {
                val irCallableReference = unwrapStaticFunctionArgument(expression.getValueArgument(0)!!)

                if (irCallableReference == null || irCallableReference.getArguments().isNotEmpty()) {
                    context.reportCompilationError(
                            "${descriptor.fqNameSafe} must take an unbound, non-capturing function or lambda",
                            irFile, expression
                    )
                    // TODO: should probably be reported during analysis.
                }

                val targetSymbol = irCallableReference.symbol
                val target = targetSymbol.owner
                val signatureTypes = target.allParameters.map { it.type } + target.returnType

                signatureTypes.forEachIndexed { index, type ->
                    type.ensureSupportedInCallbacks(
                            isReturnType = (index == signatureTypes.lastIndex),
                            reportError = ::reportError
                    )
                }

                descriptor.typeParameters.forEachIndexed { index, typeParameterDescriptor ->
                    val typeArgument = expression.getTypeArgument(typeParameterDescriptor)!!.toKotlinType()
                    val signatureType = signatureTypes[index].toKotlinType()
                    if (typeArgument.constructor != signatureType.constructor ||
                            typeArgument.isMarkedNullable != signatureType.isMarkedNullable) {
                        context.reportCompilationError(
                                "C function signature element mismatch: expected '$signatureType', got '$typeArgument'",
                                irFile, expression
                        )
                    }
                }

                IrFunctionReferenceImpl(
                        builder.startOffset, builder.endOffset,
                        expression.type,
                        targetSymbol, target.descriptor,
                        typeArgumentsCount = 0)
            }

            interop.executeFunction -> {
                val irCallableReference = unwrapStaticFunctionArgument(expression.getValueArgument(2)!!)

                if (irCallableReference == null || irCallableReference.getArguments().isNotEmpty()) {
                    context.reportCompilationError(
                            "${descriptor.fqNameSafe} must take an unbound, non-capturing function or lambda",
                            irFile, expression
                    )
                }

                val targetSymbol = irCallableReference.symbol
                val target = targetSymbol.descriptor
                val jobPointer = IrFunctionReferenceImpl(
                        builder.startOffset, builder.endOffset,
                        symbols.executeImpl.owner.valueParameters[3].type,
                        targetSymbol, target,
                        typeArgumentsCount = 0)

                builder.irCall(symbols.executeImpl).apply {
                    putValueArgument(0, expression.dispatchReceiver)
                    putValueArgument(1, expression.getValueArgument(0))
                    putValueArgument(2, expression.getValueArgument(1))
                    putValueArgument(3, jobPointer)
                }
            }

            interop.signExtend, interop.narrow -> {

                val integerTypePredicates = arrayOf(
                        IrType::isByte, IrType::isShort, IrType::isInt, IrType::isLong
                )

                val receiver = expression.extensionReceiver!!
                val typeOperand = expression.getSingleTypeArgument()
                val kotlinTypeOperand = typeOperand.toKotlinType()

                val receiverTypeIndex = integerTypePredicates.indexOfFirst { it(receiver.type) }
                val typeOperandIndex = integerTypePredicates.indexOfFirst { it(typeOperand) }

                val receiverKotlinType = receiver.type.toKotlinType()

                if (receiverTypeIndex == -1) {
                    context.reportCompilationError("Receiver's type $receiverKotlinType is not an integer type",
                            irFile, receiver)
                }

                if (typeOperandIndex == -1) {
                    context.reportCompilationError("Type argument $kotlinTypeOperand is not an integer type",
                            irFile, expression)
                }

                when (descriptor) {
                    interop.signExtend -> if (receiverTypeIndex > typeOperandIndex) {
                        context.reportCompilationError("unable to sign extend $receiverKotlinType to $kotlinTypeOperand",
                                irFile, expression)
                    }

                    interop.narrow -> if (receiverTypeIndex < typeOperandIndex) {
                        context.reportCompilationError("unable to narrow $receiverKotlinType to $kotlinTypeOperand",
                                irFile, expression)
                    }

                    else -> throw Error()
                }

                val receiverClass = symbols.integerClasses.single {
                    receiver.type.isSubtypeOf(it.owner.defaultType)
                }
                val targetClass = symbols.integerClasses.single {
                    typeOperand.isSubtypeOf(it.owner.defaultType)
                }

                val conversionSymbol = receiverClass.functions.single {
                    it.descriptor.name == Name.identifier("to${targetClass.owner.name}")
                }

                builder.irCall(conversionSymbol).apply {
                    dispatchReceiver = receiver
                }
            }

            in interop.convert -> {
                val integerClasses = symbols.allIntegerClasses
                val typeOperand = expression.getTypeArgument(0)!!
                val receiverType = expression.symbol.owner.extensionReceiverParameter!!.type
                val source = receiverType.classifierOrFail as IrClassSymbol
                assert(source in integerClasses)

                if (typeOperand is IrSimpleType && typeOperand.classifier in integerClasses && !typeOperand.hasQuestionMark) {
                    val target = typeOperand.classifier as IrClassSymbol
                    val valueToConvert = expression.extensionReceiver!!

                    if (source in symbols.signedIntegerClasses && target in symbols.unsignedIntegerClasses) {
                        // Default Kotlin signed-to-unsigned widening integer conversions don't follow C rules.
                        val signedTarget = symbols.unsignedToSignedOfSameBitWidth[target]!!
                        val widened = builder.irConvertInteger(source, signedTarget, valueToConvert)
                        builder.irConvertInteger(signedTarget, target, widened)
                    } else {
                        builder.irConvertInteger(source, target, valueToConvert)
                    }
                } else {
                    context.reportCompilationError(
                            "unable to convert ${receiverType.toKotlinType()} to ${typeOperand.toKotlinType()}",
                            irFile,
                            expression
                    )
                }
            }

            in interop.cFunctionPointerInvokes -> {
                // Replace by `invokeImpl${type}Ret`:

                val returnType =
                        expression.getTypeArgument(descriptor.typeParameters.single { it.name.asString() == "R" })!!

                returnType.checkCTypeNullability(::reportError)

                val invokeImpl = symbols.interopInvokeImpls[returnType.getClass()?.descriptor] ?:
                        context.reportCompilationError(
                                "Invocation of C function pointer with return type '${returnType.toKotlinType()}' is not supported yet",
                                irFile, expression
                        )

                builder.irCall(invokeImpl).apply {
                    putValueArgument(0, expression.extensionReceiver)

                    val varargParameter = invokeImpl.owner.valueParameters[1]
                    val varargArgument = IrVarargImpl(
                            startOffset, endOffset, varargParameter.type, varargParameter.varargElementType!!
                    ).apply {
                        descriptor.valueParameters.forEach {
                            this.addElement(expression.getValueArgument(it)!!)
                        }
                    }
                    putValueArgument(varargParameter.index, varargArgument)
                }
            }

            interop.objCObjectInitBy -> {
                val intrinsic = interop.objCObjectInitBy.name

                val argument = expression.getValueArgument(0)!!
                val constructedClass =
                        ((argument as? IrCall)?.descriptor as? ClassConstructorDescriptor)?.constructedClass

                if (constructedClass == null) {
                    context.reportCompilationError("Argument of '$intrinsic' must be a constructor call",
                            irFile, argument)
                }

                val extensionReceiver = expression.extensionReceiver!!
                if (extensionReceiver !is IrGetValue ||
                        extensionReceiver.descriptor != constructedClass.thisAsReceiverParameter) {

                    context.reportCompilationError("Receiver of '$intrinsic' must be a 'this' of the constructed class",
                            irFile, extensionReceiver)
                }

                expression
            }

            else -> expression
        }
    }

    private fun IrBuilderWithScope.irConvertInteger(
            source: IrClassSymbol,
            target: IrClassSymbol,
            value: IrExpression
    ): IrExpression {
        val conversion = symbols.integerConversions[source to target]!!
        return irCall(conversion.owner).apply {
            if (conversion.owner.dispatchReceiverParameter != null) {
                dispatchReceiver = value
            } else {
                extensionReceiver = value
            }
        }
    }

    private fun IrType.ensureSupportedInCallbacks(isReturnType: Boolean, reportError: (String) -> Nothing) {
        this.checkCTypeNullability(reportError)

        if (isReturnType && this.isUnit()) {
            return
        }

        if (this.isPrimitiveType()) {
            return
        }

        if (UnsignedTypes.isUnsignedType(this.toKotlinType()) && !this.containsNull()) {
            return
        }

        if (this.getClass()?.descriptor == interop.cPointer) {
            return
        }

        reportError("Type ${this.toKotlinType()} is not supported in callback signature")
    }

    private fun IrType.checkCTypeNullability(reportError: (String) -> Nothing) {
        if (this.isNullablePrimitiveType() || UnsignedTypes.isUnsignedType(this.toKotlinType()) && this.containsNull()) {
            reportError("Type ${this.toKotlinType()} must not be nullable when used in C function signature")
        }

        if (this.getClass() == interop.cPointer && !this.isSimpleTypeWithQuestionMark) {
            reportError("Type ${this.toKotlinType()} must be nullable when used in C function signature")
        }
    }

    private fun unwrapStaticFunctionArgument(argument: IrExpression): IrFunctionReference? {
        if (argument is IrFunctionReference) {
            return argument
        }

        // Otherwise check whether it is a lambda:

        // 1. It is a container with two statements and expected origin:

        if (argument !is IrContainerExpression || argument.statements.size != 2) {
            return null
        }
        if (argument.origin != IrStatementOrigin.LAMBDA && argument.origin != IrStatementOrigin.ANONYMOUS_FUNCTION) {
            return null
        }

        // 2. First statement is an empty container (created during local functions lowering):

        val firstStatement = argument.statements.first()

        if (firstStatement !is IrContainerExpression || firstStatement.statements.size != 0) {
            return null
        }

        // 3. Second statement is IrCallableReference:

        return argument.statements.last() as? IrFunctionReference
    }
}

private fun IrCall.getSingleTypeArgument(): IrType {
    val typeParameter = descriptor.original.typeParameters.single()
    return getTypeArgument(typeParameter)!!
}

private fun IrBuilder.irFloat(value: Float) =
        IrConstImpl.float(startOffset, endOffset, context.irBuiltIns.floatType, value)

private fun IrBuilder.irDouble(value: Double) =
        IrConstImpl.double(startOffset, endOffset, context.irBuiltIns.doubleType, value)

private fun Annotations.hasAnnotation(descriptor: ClassDescriptor) = this.hasAnnotation(descriptor.fqNameSafe)
