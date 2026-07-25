package org.folio.factory.app;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "org.folio.factory")
@EntityScan(basePackages = "org.folio.factory.core.domain")
@EnableJpaRepositories(basePackages = "org.folio.factory.core")
@EnableScheduling
public class FactoryApplication {

    public static void main(String[] args) {
        configureCodemieProxy();
        SpringApplication.run(FactoryApplication.class, args);
    }

    private static void configureCodemieProxy() {
        if (isNonBlank(System.getenv("ANTHROPIC_API_KEY"))) {
            return;
        }
        String baseUrl = System.getenv("ANTHROPIC_BASE_URL");
        if (!isNonBlank(baseUrl)) {
            int port = detectCodemieProxyPort();
            if (port > 0) {
                baseUrl = "http://localhost:" + port;
            }
        }
        if (isNonBlank(baseUrl)) {
            System.setProperty("spring.ai.anthropic.api-key", "proxy-handled");
            System.setProperty("spring.ai.anthropic.base-url", baseUrl);
            System.out.printf("%n[factory] CodeMie proxy - routing Anthropic API to %s%n%n", baseUrl);
        } else {
            System.err.println("\n[factory] CodeMie proxy not found — set ANTHROPIC_API_KEY or ANTHROPIC_BASE_URL\n");
        }
    }

    private static int detectCodemieProxyPort() {
        String script =
            "Get-Process node -ErrorAction SilentlyContinue | ForEach-Object {" +
            " $id = $_.Id;" +
            " $cmd = (Get-WmiObject Win32_Process -Filter \"ProcessId=$id\").CommandLine;" +
            " if ($cmd -match 'codemie') {" +
            "  Get-NetTCPConnection -OwningProcess $id -ErrorAction SilentlyContinue" +
            "  | Where-Object State -eq 'Listen'" +
            "  | Select-Object -First 1 -ExpandProperty LocalPort" +
            " }" +
            "}";
        try {
            Process proc = new ProcessBuilder("powershell", "-Command", script)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.matches("\\d+")) {
                        proc.destroyForcibly();
                        return Integer.parseInt(line);
                    }
                }
            }
            proc.waitFor();
        } catch (Exception e) {
            System.err.println("[factory] CodeMie port detection error: " + e.getMessage());
        }
        return -1;
    }

    private static boolean isNonBlank(String s) {
        return s != null && !s.isBlank();
    }
}
