package io.github.jessielee3260.upjasperreport6to7;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

@SpringBootApplication
@ConfigurationPropertiesScan
public class UpJasperReport6To7Application {

    private static final Logger log = LoggerFactory.getLogger(UpJasperReport6To7Application.class);

    public static void main(String[] args) {
        ConfigurableApplicationContext ctx = SpringApplication.run(UpJasperReport6To7Application.class, args);
        Environment env = ctx.getEnvironment();
        if (shouldOpenBrowser(env)) {
            String url = "http://localhost:" + env.getProperty("server.port", "31415") + "/";
            log.info("開啟瀏覽器：{}", url);
            browse(url);
        }
    }

    /**
     * web 模式（auto-run=false）且 upj.open-browser 未關閉時，啟動後自動開啟瀏覽器。
     * cli 批次模式（--spring.profiles.active=cli，upj.auto-run=true）不開瀏覽器。
     */
    static boolean shouldOpenBrowser(Environment env) {
        boolean autoRun = Boolean.parseBoolean(env.getProperty("upj.auto-run", "false"));
        boolean openBrowser = Boolean.parseBoolean(env.getProperty("upj.open-browser", "true"));
        return openBrowser && !autoRun;
    }

    /**
     * 開啟預設瀏覽器：
     * Desktop.browse 優先，不支援時 Windows 用 rundll32 url.dll 備援。
     */
    static void browse(String url) {
        if (Desktop.isDesktopSupported()) {
            Desktop desktop = Desktop.getDesktop();
            try {
                desktop.browse(new URI(url));
                return;
            } catch (IOException | URISyntaxException e) {
                log.warn("Desktop.browse 失敗（{}），改試備援方式", e.getMessage());
            }
        }
        try {
            Runtime.getRuntime().exec("rundll32 url.dll,FileProtocolHandler " + url);
        } catch (IOException e) {
            log.warn("自動開啟瀏覽器失敗，請手動開啟：{}", e.getMessage());
        }
    }
}
