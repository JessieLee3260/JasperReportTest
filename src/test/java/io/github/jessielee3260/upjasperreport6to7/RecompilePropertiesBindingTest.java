package io.github.jessielee3260.upjasperreport6to7;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回歸測試：application.properties 的 upj.* 必須能成功綁定。
 *
 * 路徑欄位刻意是 String：若改成 java.nio.file.Path，Spring 的 String→Path
 * 轉換會把 "../sample-reports/..." 當資源路徑正規化而拋
 * IllegalArgumentException（The resource path [...] has been normalized to [null]），
 * 導致 APPLICATION FAILED TO START。此測試確保綁定路徑不會再壞掉。
 */
@SpringBootTest
class RecompilePropertiesBindingTest {

    @Autowired
    RecompileProperties props;

    @Test
    void upjPropertiesBindFromApplicationProperties() {
        assertEquals("../sample-reports/WEB-INF", props.sourceDir());
        assertEquals("out-jasper", props.outDir());
        assertEquals("recompile-report.json", props.reportFile());
        assertTrue(props.dryRun());
        assertFalse(props.inPlace());
    }
}
