package org.kotlinnative.translator

import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.kotlinnative.translator.llvm.LLVMBuilder

class FileTranslator(val state: TranslationState, val file: KtFile) {

    private var codeBuilder = LLVMBuilder(state.arm)

    fun generateCode(): String {
        codeBuilder.clean()
        generateFileBody()
        return codeBuilder.toString()
    }

    private fun generateFileBody() {
        for (declaration in file.declarations) {
            when (declaration) {
                is KtNamedFunction -> {
                    val function = FunctionCodegen(state, declaration, codeBuilder)
                    state.functions.put(function.name, function)
                }
                is KtClass -> {
                    val codegen = ClassCodegen(state, declaration, codeBuilder)
                    state.classes.put(declaration.name!!, codegen)
                }
                is KtProperty -> {
                    val property = PropertyCodegen(state, declaration, codeBuilder)
                    state.properties.put(declaration.name!!, property)
                }
            }
        }

        for (property in state.properties.values) {
            property.generate()
        }

        for (clazz in state.classes.values) {
            clazz.generate()
        }

        for (function in state.functions.values) {
            function.generate()
        }

    }

}

