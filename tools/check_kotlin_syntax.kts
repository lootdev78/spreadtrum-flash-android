import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtPsiFactory
import java.io.File
val root=File(args.firstOrNull()?:"."); val d=Disposer.newDisposable(); var n=0
try { val e=KotlinCoreEnvironment.createForProduction(d,CompilerConfiguration(),EnvironmentConfigFiles.JVM_CONFIG_FILES); val f=KtPsiFactory(e.project,false)
root.walkTopDown().filter{it.isFile&&it.extension=="kt"}.forEach{p-> val k=f.createFile(p.name,p.readText()); PsiTreeUtil.collectElementsOfType(k,PsiErrorElement::class.java).forEach{x->System.err.println("${p.relativeTo(root)}:${k.text.take(x.textOffset).count{it=='\n'}+1}: ${x.errorDescription}");n++}}
} finally { Disposer.dispose(d) }
check(n==0){"$n Kotlin-Syntaxfehler"}; println("Kotlin-Syntax OK")
