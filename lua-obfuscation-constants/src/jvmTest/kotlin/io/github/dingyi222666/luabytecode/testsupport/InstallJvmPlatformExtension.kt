package io.github.dingyi222666.luabytecode.testsupport

import io.github.dingyi222666.luabytecode.platform.jvm.JvmPlatform
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * Auto-registered JUnit extension (see
 * `META-INF/services/org.junit.jupiter.api.extension.Extension` plus
 * `junit-platform.properties`): installs the JVM platform services before the
 * first test of every class, so pass code sees the classpath asset source and
 * env/stderr debug switches exactly like the CLI does.
 */
class InstallJvmPlatformExtension : BeforeAllCallback {
    override fun beforeAll(context: ExtensionContext) {
        JvmPlatform.install()
    }
}
