package io.github.jessielee3260.upjasperreport6to7;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 任何 /api 請求都更新 {@link BrowserShutdownWatcher} 的活動時間戳記
 * （掃描/狀態輪詢/批次執行期間的輪詢都會自動續命；頁面開著就不會被安全網停掉）。
 */
@Component
public class ApiActivityFilter extends OncePerRequestFilter {

    private final BrowserShutdownWatcher watcher;

    public ApiActivityFilter(BrowserShutdownWatcher watcher) {
        this.watcher = watcher;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        watcher.recordActivity();
        chain.doFilter(req, res);
    }
}
