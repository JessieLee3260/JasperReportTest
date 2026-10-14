package io.github.jessielee3260.upjasperreport6to7;

import net.sf.jasperreports.engine.DefaultJasperReportsContext;
import net.sf.jasperreports.engine.JRPropertiesUtil;
import net.sf.jasperreports.engine.design.JRCompiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CompilerClasspathTest {

    @Test
    void resolveReturnsNonEmptyClasspath() {
        String classpath = CompilerClasspath.resolve();

        assertNotNull(classpath);
        assertFalse(classpath.isBlank());
    }

    @Test
    void applySetsJasperReportsCompilerClasspathProperty() {
        var context = DefaultJasperReportsContext.getInstance();

        String classpath = CompilerClasspath.apply(context);
        String applied = JRPropertiesUtil.getInstance(context).getProperty(JRCompiler.COMPILER_CLASSPATH);

        assertEquals(classpath, applied);
    }

    @Test
    void newClassLoaderCanResolveJasperReportsClasses() throws Exception {
        ClassLoader classLoader = CompilerClasspath.newClassLoader();

        assertNotNull(classLoader);
        assertNotNull(Class.forName("net.sf.jasperreports.engine.JasperReport", false, classLoader));
    }
}
