/*
 * Portfolio
 * Copyright (C) 2020  Vitalii Ananev <an-vitek@ya.ru>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ru.portfolio.portfolio.parser;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.portfolio.portfolio.parser.psb.PsbBrokerReport;
import ru.portfolio.portfolio.parser.psb.PsbReportTableFactory;
import ru.portfolio.portfolio.parser.uralsib.UralsibBrokerReport;
import ru.portfolio.portfolio.parser.uralsib.UralsibReportTableFactory;
import ru.portfolio.portfolio.view.ForeignExchangeRateService;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.zip.ZipInputStream;

@RestController
@RequiredArgsConstructor
@Slf4j
public class ReportRestController {
    private static final Path reportBackupPath = Paths.get(
            System.getProperty("user.home", ""),
            "portfolio-report-backups");
    private final ReportParserService reportParserService;
    private final ForeignExchangeRateService foreignExchangeRateService;

    @PostMapping("/reports")
    public ResponseEntity<String> post(@RequestParam("reports") MultipartFile[] reports,
                                       @RequestParam(name = "format", required = false) String format) {
        if (format == null || format.isEmpty()) {
            format = "psb";
        }
        BrockerType brocker = BrockerType.valueOf(format.toUpperCase());
        List<Exception> exceptions = new ArrayList<>();
        for (MultipartFile report : reports) {
            try {
                if (report == null || report.isEmpty()) {
                    continue;
                }
                long t0 = System.nanoTime();
                Path path = saveToBackup(brocker, report);
                String originalFileName = report.getOriginalFilename();
                switch (brocker) {
                    case PSB:
                        parsePsbReport(report);
                        break;
                    case URALSIB:
                        if (originalFileName != null && !originalFileName.contains("_invest_")) {
                            log.warn("Рекомендуется загружать отчеты содержащие в имени файла слово 'invest'");
                        }
                        if (originalFileName != null && !originalFileName.toLowerCase().endsWith(".zip")) {
                            parseUralsibReport(report);
                        } else {
                            parseUralsibZipReport(report);
                        }
                        break;
                    default:
                        throw new IllegalArgumentException("Неизвестный формат " + format);
                }
                log.info("Загрузка отчета {} завершена за {}, бекап отчета сохранен в {}", report.getOriginalFilename(),
                        Duration.ofNanos(System.nanoTime() - t0), path.toAbsolutePath());
            } catch (Exception e) {
                exceptions.add(e);
            }
        }
        if (exceptions.isEmpty()) {
            return ResponseEntity.ok("ok");
        } else {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(exceptions.stream()
                            .map(e -> {
                                StringWriter sw = new StringWriter();
                                PrintWriter pw = new PrintWriter(sw);
                                e.printStackTrace(pw);
                                return sw.toString().replace("\n", "</br>");
                            }).collect(Collectors.joining("</br></br> - ",
                                    "<b>Ошибка загрузки отчетов</b></br></br> - ",
                                    "")));
        }
    }

    /**
     * @return backup file
     */
    private Path saveToBackup(BrockerType brocker, MultipartFile report) throws IOException {
        byte[] bytes = report.getBytes();
        String originalFilename = report.getOriginalFilename();
        Path backupPath = reportBackupPath.resolve(brocker.name().toLowerCase());
        Files.createDirectories(backupPath);
        Path path = backupPath.resolve((originalFilename != null) ?
                originalFilename :
                UUID.randomUUID().toString());
        for (int i = 1; i < 1e6; i++) {
            if (!Files.exists(path)) {
                break;
            }
            path = backupPath.resolve((originalFilename != null) ?
                    "Копия " + i + " - " + (originalFilename) :
                    UUID.randomUUID().toString());
        }
        Files.write(path, bytes);
        return path;
    }

    private void parsePsbReport(MultipartFile report) {
        try (PsbBrokerReport brockerReport = new PsbBrokerReport(report.getOriginalFilename(), report.getInputStream())) {
            ReportTableFactory reportTableFactory = new PsbReportTableFactory(brockerReport);
            reportParserService.parse(reportTableFactory);
        } catch (Exception e) {
            String error = "Произошла ошибка парсинга отчета " + report.getOriginalFilename();
            log.warn(error, e);
            throw new RuntimeException(error, e);
        }
    }

    private void parseUralsibReport(MultipartFile report) {
        parseUralsibReport(report, () -> {
            try {
                return new UralsibBrokerReport(report.getOriginalFilename(), report.getInputStream());
            } catch (Exception e) {
                String error = "Отчет предоставлен в неверном формате " + report.getOriginalFilename();
                log.warn(error, e);
                throw new RuntimeException(error, e);
            }
        });
    }

    private void parseUralsibZipReport(MultipartFile report) {
        try (ZipInputStream zis = new ZipInputStream(report.getInputStream())) {
            parseUralsibReport(report, () -> {
                try {
                    return new UralsibBrokerReport(zis);
                } catch (Exception e) {
                    String error = "Отчет предоставлен в неверном формате " + report.getOriginalFilename();
                    log.warn(error, e);
                    throw new RuntimeException(error, e);
                }
            });
        } catch (IOException e) {
            String error = "Не могу открыть zip архив " + report.getOriginalFilename();
            log.warn(error, e);
            throw new RuntimeException(error, e);
        }
    }

    private void parseUralsibReport(MultipartFile report, Supplier<UralsibBrokerReport> reportSupplizer) {
        try (UralsibBrokerReport brockerReport = reportSupplizer.get()) {
            ReportTableFactory reportTableFactory = new UralsibReportTableFactory(brockerReport, foreignExchangeRateService);
            reportParserService.parse(reportTableFactory);
        } catch (Exception e) {
            String error = "Произошла ошибка парсинга отчета " + report.getOriginalFilename();
            log.warn(error, e);
            throw new RuntimeException(error, e);
        }
    }
}
