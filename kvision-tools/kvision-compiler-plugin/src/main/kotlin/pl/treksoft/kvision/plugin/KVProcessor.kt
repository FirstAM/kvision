/*
 * Copyright (c) 2017-present Robert Jaros
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package pl.treksoft.kvision.plugin

import de.jensklingenberg.mpapt.common.canonicalFilePath
import de.jensklingenberg.mpapt.common.methods
import de.jensklingenberg.mpapt.model.AbstractProcessor
import de.jensklingenberg.mpapt.model.Element
import de.jensklingenberg.mpapt.model.RoundEnvironment
import de.jensklingenberg.mpapt.utils.KotlinPlatformValues
import org.jetbrains.kotlin.backend.common.descriptors.allParameters
import org.jetbrains.kotlin.cli.common.config.kotlinSourceRoots
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.descriptors.ParameterDescriptor
import org.jetbrains.kotlin.js.descriptorUtils.getJetTypeFqName
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.types.KotlinType
import pl.treksoft.kvision.annotations.KVService
import java.io.File

class KVProcessor : AbstractProcessor() {

    override fun isTargetPlatformSupported(platform: TargetPlatform): Boolean {
        val targetName = platform.first().platformName
        return when (targetName) {
            KotlinPlatformValues.JS -> false
            KotlinPlatformValues.JVM -> true
            KotlinPlatformValues.NATIVE -> false
            else -> {
                log(targetName)
                false
            }
        }
    }

    @Suppress("MaxLineLength", "ComplexMethod", "NestedBlockDepth")
    override fun process(roundEnvironment: RoundEnvironment) {
        val isCommon = this.configuration.kotlinSourceRoots.find { !it.isCommon } == null
        if (isCommon) {
            roundEnvironment.getElementsAnnotatedWith(KVService::class.java.name).forEach {
                if (it is Element.ClassElement && it.classDescriptor.name.asString().startsWith("I")
                    && it.classDescriptor.name.asString().endsWith("Service")
                ) {
                    tailrec fun findBuildFolder(path: String): String {
                        val preSrcDir = path.substringBeforeLast("/src")
                        return if (path == preSrcDir || File(preSrcDir, "build").isDirectory) {
                            "$preSrcDir/build"
                        } else {
                            findBuildFolder(preSrcDir)
                        }
                    }

                    val cl = it.classDescriptor
                    val buildFolder = cl.canonicalFilePath()?.let { path -> findBuildFolder(path) }
                    val genRootDir = File(buildFolder, "generated-src").apply {
                        mkdirs()
                    }
                    val packageName = cl.containingDeclaration.fqNameSafe.asString()
                    val iName = cl.name.asString()
                    val baseName = iName.drop(1)
                    val commonCode = StringBuilder().apply {
                        appendln("//")
                        appendln("// GENERATED by KVision")
                        appendln("//")
                        appendln("package $packageName")
                        appendln()
                        appendln("import kotlinx.coroutines.CoroutineStart")
                        appendln("import kotlinx.coroutines.GlobalScope")
                        appendln("import kotlinx.coroutines.launch")
                        appendln("import pl.treksoft.kvision.remote.KVServiceManager")
                        appendln()
                        appendln("expect class $baseName : $iName")
                        appendln()
                        appendln("object ${baseName}Manager : KVServiceManager<$baseName>($baseName::class) {")
                        appendln("    init {")
                        appendln("        GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) {")
                        cl.methods().forEach {
                            when {
                                it.returnType.toString().startsWith("RemoteData") ->
                                    appendln("            bindTabulatorRemote($iName::${it.name})")
                                else -> appendln("            bind($iName::${it.name})")
                            }
                        }
                        appendln("        }")
                        appendln("    }")
                        appendln("}")
                    }.toString()
                    val commonDestinationDir = File(
                        genRootDir,
                        "common" + File.separator + packageName.replace('.', File.separatorChar)
                    ).apply {
                        mkdirs()
                    }
                    val commonFile = File(commonDestinationDir, "${baseName}Manager.kt")
                    if (commonFile.exists()) {
                        val content = commonFile.readText()
                        if (content != commonCode) {
                            commonFile.writeText(commonCode)
                        }
                    } else {
                        commonFile.writeText(commonCode)
                    }
                    val frontendCode = StringBuilder().apply {
                        appendln("//")
                        appendln("// GENERATED by KVision")
                        appendln("//")
                        appendln("package $packageName")
                        appendln()
                        appendln("import pl.treksoft.kvision.remote.KVRemoteAgent")
                        getTypes(cl.methods()).sorted().forEach {
                            appendln("import $it")
                        }
                        appendln()
                        appendln("actual class $baseName : $iName, KVRemoteAgent<$baseName>(${baseName}Manager) {")
                        cl.methods().forEach {
                            val name = it.name
                            val params = it.allParameters.drop(1)
                            val wsMethod =
                                if (params.size == 2)
                                    params.first().type.toString().startsWith("ReceiveChannel")
                                else false
                            if (!wsMethod) {
                                if (params.isNotEmpty()) {
                                    when {
                                        it.returnType.toString().startsWith("RemoteData") -> appendln(
                                            "    override suspend fun $name(${getParameterList(
                                                params
                                            )}) = ${it.returnType.toString()}()"
                                        )
                                        else -> appendln(
                                            "    override suspend fun $name(${getParameterList(params)}) = call($iName::$name, ${getParameterNames(
                                                params
                                            )})"
                                        )
                                    }
                                } else {
                                    appendln("    override suspend fun $name() = call($iName::$name)")
                                }
                            } else {
                                appendln("    override suspend fun $name(${getParameterList(params)}) {}")
                                val type1 = params[0].type.toString().replace("ReceiveChannel", "SendChannel")
                                val type2 = params[1].type.toString().replace("SendChannel", "ReceiveChannel")
                                appendln("    suspend fun $name(handler: suspend ($type1, $type2) -> Unit) = webSocket($iName::$name, handler)")
                            }
                        }
                        appendln("}")
                    }.toString()
                    val frontendDestinationDir = File(
                        genRootDir,
                        "frontend" + File.separator + packageName.replace('.', File.separatorChar)
                    ).apply {
                        mkdirs()
                    }
                    val frontendFile = File(frontendDestinationDir, "${baseName}.kt")
                    if (frontendFile.exists()) {
                        val content = frontendFile.readText()
                        if (content != frontendCode) {
                            frontendFile.writeText(frontendCode)
                        }
                    } else {
                        frontendFile.writeText(frontendCode)
                    }
                }
            }
        }
    }

    private fun getParameterList(params: List<ParameterDescriptor>): String {
        return params.joinToString(", ") {
            "${it.name.asString()}: ${it.type}"
        }
    }

    private fun getParameterNames(params: List<ParameterDescriptor>): String {
        return params.joinToString(", ") {
            it.name.asString()
        }
    }

    private fun getTypes(type: KotlinType): Set<String> {
        return if (type.arguments.isNotEmpty()) {
            (type.arguments.flatMap { getTypes(it.type) } + type.getJetTypeFqName(false)).toSet()
        } else {
            setOf(type.getJetTypeFqName(false))
        }
    }

    private fun getTypes(methods: Collection<CallableMemberDescriptor>): Set<String> {
        return methods.flatMap { m ->
            m.allParameters.drop(1).flatMap { p ->
                getTypes(p.type)
            }.toSet() + (m.returnType?.let { getTypes(it) } ?: setOf())
        }.filterNot {
            it.startsWith("kotlin.collections.") || it.startsWith("kotlin.")
        }.toSet()
    }

    override fun getSupportedAnnotationTypes(): Set<String> = setOf(
        KVService::class.java.name
    )

}
