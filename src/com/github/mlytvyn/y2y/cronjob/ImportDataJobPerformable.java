
package com.github.mlytvyn.y2y.cronjob;

import com.github.mlytvyn.y2y.hac.flexiblesearch.Y2YFlexibleSearch;
import de.hybris.platform.core.Registry;
import de.hybris.platform.cronjob.enums.CronJobResult;
import de.hybris.platform.cronjob.enums.CronJobStatus;
import de.hybris.platform.servicelayer.cronjob.AbstractJobPerformable;
import de.hybris.platform.servicelayer.cronjob.PerformResult;
import de.hybris.platform.servicelayer.impex.ImportConfig;
import de.hybris.platform.servicelayer.impex.ImportResult;
import de.hybris.platform.servicelayer.impex.ImportService;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import de.hybris.platform.servicelayer.session.SessionService;
import de.hybris.platform.servicelayer.type.TypeService;
import de.hybris.platform.util.Config;
import de.hybris.platform.util.Utilities;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.github.mlytvyn.y2y.enums.Database;
import com.github.mlytvyn.y2y.exceptions.ExportException;
import com.github.mlytvyn.y2y.model.ExportPkMatrixModel;
import com.github.mlytvyn.y2y.model.ExportRuleModel;
import com.github.mlytvyn.y2y.model.ImportDataCronJobModel;
import com.github.mlytvyn.y2y.model.SourceSystemConfigurationModel;

import javax.annotation.Resource;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Service("importDataJobPerformable")
public class ImportDataJobPerformable<T extends ImportDataCronJobModel> extends AbstractJobPerformable<T> {

    private static final Logger LOG = LogManager.getLogger();
    @Resource(name = "importService")
    private ImportService importService;
    @Resource(name = "typeService")
    private TypeService typeService;
    @Value("${HYBRIS_DATA_DIR}")
    private String dataFolder;

    @Override
    public PerformResult perform(final T cronJob) {
        try {
            LOG.debug("[Import : {}, export rules: {}] started", cronJob::getCode, () -> cronJob.getExportRules().size());
            clearPKMatrix(cronJob);
            clearDataFolder(cronJob);
            cronJob.getExportRules().forEach(exportRule -> {
                executeExportRule(cronJob, exportRule, null, false);
            });
            LOG.debug("[Import : {}, export rules: {}] completed", cronJob::getCode, () -> cronJob.getExportRules().size());
        } catch (final ExportException e) {
            LOG.warn(e);
            return new PerformResult(CronJobResult.FAILURE, CronJobStatus.ABORTED);
        }
        return new PerformResult(CronJobResult.SUCCESS, CronJobStatus.FINISHED);
    }

    private void clearDataFolder(final T cronJob) {
        if (cronJob.isSaveImportImpexes()) {
            try {
                final Path dir = Paths.get(dataFolder, "externalData", "impexes", cronJob.getCode());
                if (Files.exists(dir)) {
                    try (final Stream<Path> files = Files.walk(dir).sorted(Comparator.reverseOrder())) {
                        files.map(Path::toFile)
                            .forEach(File::delete);
                    }
                }
            } catch (final IOException e) {
                throw new ExportException(e);
            }
        }
    }

    private void executeExportRule(final T cronJob, final ExportRuleModel exportRule, final ExportRuleModel parentExportRule, final boolean clearParentSeed) {
        LOG.debug("[Export rule : {}] started", exportRule::getName);
        final List<ExportPkMatrixModel> parentSeed;
        final String parentSeedPks;
        if (parentExportRule == null) {
            parentSeed = null;
            parentSeedPks = null;
        } else {
            parentSeed = getExportPksMatrix(parentExportRule);
            parentSeedPks = parentSeed.stream()
                .map(ExportPkMatrixModel::getSourcePK)
                .collect(Collectors.joining("','", "'", "'"));
        }
        if (clearAbortRequestedIfNeeded(cronJob)) {
            throw new ExportException("Requested abort");
        }
        final long count = getCountForExportRule(cronJob, exportRule, parentSeedPks);

        final int batchSize = exportRule.getBatchSize();
        final int iterations = BigDecimal.valueOf(count)
            .divide(BigDecimal.valueOf(batchSize), RoundingMode.UP)
            .intValue();

        LOG.debug("[Export rule : {}, batch size: {}, iterations {}, total: {}]", exportRule::getName, () -> batchSize, () -> iterations, () -> count);
        IntStream.iterate(0, i -> i + batchSize)
            .limit(iterations)
            .forEach(seed -> {
                if (clearAbortRequestedIfNeeded(cronJob)) {
                    throw new ExportException("Requested abort");
                }
                importData(cronJob, exportRule, count, batchSize, seed, parentSeedPks);
                executeChildExportRulesForSeed(cronJob, exportRule, true);
            });
        if (parentSeed != null && clearParentSeed) {
            LOG.debug("[Export rule : {}] removing {} temporary mapping pks from parent seed: {}", exportRule::getName, parentSeed::size, parentExportRule::getName);
            modelService.removeAll(parentSeed);
        }
        LOG.debug("[Export rule : {}] completed", exportRule::getName);
    }

    private void executeChildExportRulesForSeed(final T cronJob, final ExportRuleModel exportRule, final boolean lastIteration) {
        final Set<ExportRuleModel> childExportRules = exportRule.getChildExportRules();
        if (!childExportRules.isEmpty()) {
            LOG.debug("[Export rule : {}] child export rules started: {}", exportRule::getName, childExportRules::size);
            final Iterator<ExportRuleModel> iterator = childExportRules.iterator();
            do {
                executeExportRule(cronJob, iterator.next(), exportRule, !iterator.hasNext() && lastIteration);
            } while (iterator.hasNext());
            LOG.debug("[Export rule : {}] child export rules completed: {}", exportRule::getName, childExportRules::size);
        }
    }

    private List<ExportPkMatrixModel> getExportPksMatrix(final ExportRuleModel exportRule) {
        final FlexibleSearchQuery query = new FlexibleSearchQuery("SELECT {" + ExportPkMatrixModel.PK + "} FROM {" + ExportPkMatrixModel._TYPECODE + "} WHERE {" + ExportPkMatrixModel.RULEPK + "} = ?rulePk");
        query.addQueryParameter("rulePk", exportRule);
        return flexibleSearchService.<ExportPkMatrixModel>search(query).getResult();
    }

    private void importData(final T cronJob, final ExportRuleModel exportRule, final long count, final Integer batchSize, final int seed, final String parentSeedPks) {
        LOG.debug("[Export rule : {}, seed: {} of {}] started", exportRule::getName, () -> seed, () -> count - 1);
        // offset and limit must be specified directly on Query
        final String exportQuery = replaceQueryParams(cronJob, exportRule, Optional.ofNullable(parentSeedPks)
            .map(sourcePks -> {
                if (!exportRule.getExportQuery().contains("?pks")) {
                    throw new ExportException("[Export rule: " + exportRule.getName() + "] Child export query must have `?pks` as where clause");
                }
                final String query = exportRule.getExportQuery()
                    .replace("?pks", parentSeedPks);
                return limitForDatabase(batchSize, seed, query, cronJob.getSourceSystem().getDatabase());
            })
            .orElseGet(() -> limitForDatabase(batchSize, seed, exportRule.getExportQuery(), cronJob.getSourceSystem().getDatabase())));

        final List<Map<String, String>> response = getExternalData(exportQuery, exportRule, batchSize, cronJob.getSourceSystem());

        if (!response.isEmpty()) {
            final StringBuilder impex = buildImportImpex(exportRule, response);

            final ImportConfig importConfig = new ImportConfig();
            importConfig.setScript(impex.toString());
            importConfig.setLegacyMode(false);
            importConfig.setEnableCodeExecution(true);

            saveImportImpex(cronJob, exportRule, count, seed, impex);

            final ImportResult importResult = importService.importData(importConfig);
            LOG.debug("Completed impex import with result: {}", () -> importResult.isSuccessful() ? "success" : "with fails");
        }

        LOG.debug("[Export rule : {}, seed: {} - {}] completed", exportRule::getName, () -> seed, () -> count - 1);
    }

    private String limitForDatabase(final Integer batchSize, final int seed, final String query, final Database database) {
        if (Database.SQLSERVER == database) {
            return String.format("%s OFFSET %s ROWS FETCH NEXT %s ROWS ONLY", query, seed, batchSize);
        }
        return String.format("%s LIMIT %s OFFSET %s", query, batchSize - 1, seed);
    }

    private StringBuilder buildImportImpex(final ExportRuleModel exportRule, final List<Map<String, String>> response) {
        final StringBuilder impex = new StringBuilder(exportRule.getImportHeader());
        if (!exportRule.getChildExportRules().isEmpty()) {
            // don't create ExportPKMatrix entries for export rules without children, only child rule can get benefits from parent's PKs
            impex.append("\n").append(getScriptForPkMatrix(exportRule));
        }
        response.forEach(entry -> {
            impex.append("\n").append(";").append(entry.get("pk"));
            exportRule.getColumns().forEach(column -> {
                if (entry.containsKey(column)) {
                    impex.append(";").append(Optional.ofNullable(entry.get(column))
                        .map(value -> StringEscapeUtils.unescapeHtml4(value).replaceAll("\"", "'"))
                        .map(value -> '"' + value + '"')
                        .orElse(""));
                } else {
                    //if column was not selected it will be inserted by import script,
                    //useful in case of Product import and catalog selection
                    impex.append(";");
                }
            });
        });
        return impex;
    }

    private void saveImportImpex(final T cronJob, final ExportRuleModel exportRule, final long count, final int seed, final StringBuilder impex) {
        if (cronJob.isSaveImportImpexes()) {
            try {
                final Path dir = Paths.get(dataFolder, "externalData", "impexes", cronJob.getCode());
                Files.createDirectories(dir);
                final Path file = Paths.get(dir.toString(), String.format("%s %s - %s (%s).impex",
                    exportRule.getName(), seed, count - 1,
                    DateTimeFormatter.ISO_DATE_TIME.format(LocalDateTime.now()).replace(":", "-")));
                Files.write(file, impex.toString().getBytes(StandardCharsets.UTF_8));
            } catch (final IOException e) {
                throw new ExportException(e);
            }
        }
    }

    /**
     * Import Profile params will override params specified in Export Rule
     */
    private String replaceQueryParams(final T cronJob, final ExportRuleModel exportRule, String query) {
        final Map<String, String> params = new HashMap<>(exportRule.getParams());
        params.putAll(cronJob.getParams());
        for (final Map.Entry<String, String> entry : params.entrySet()) {
            final String boxedValue = entry.getValue();
            final String value;
            if (boxedValue.endsWith("_byte") || boxedValue.endsWith("_int") || boxedValue.endsWith("_long")
                || boxedValue.endsWith("_float") || boxedValue.endsWith("_double")) {
                value = boxedValue;
            } else {
                value = String.format("'%s'", boxedValue);
            }
            query = query.replace("?" + entry.getKey(), value);
        }
        return query;
    }

    private void clearPKMatrix(final T cronJob) {
        Connection conn = null;
        PreparedStatement pstmt = null;
        try {
            LOG.debug("[Export : {}] clearing PKs Matrix started", cronJob::getCode);
            conn = Registry.getCurrentTenant().getDataSource().getConnection();
            final String statement;
            if (Config.isMySQLUsed() || Config.isHanaUsed()) {
                final String table = typeService.getComposedTypeForClass(ExportPkMatrixModel.class).getTable();
                statement = "DELETE FROM `" + table + "` WHERE PK > 0;";
            } else {
                throw new ExportException("Unsupported DB Server. DB: " + Config.getDatabase());
            }
            pstmt = conn.prepareStatement(statement);
            pstmt.execute();
        } catch (final SQLException e) {
            LOG.error("Cannot clean PKs matrix table", e);
        } finally {
            LOG.debug("[Export : {}] clearing PKs Matrix completed", cronJob::getCode);
            Utilities.tryToCloseJDBC(conn, pstmt, null);
        }
    }

    private String getScriptForPkMatrix(final ExportRuleModel exportRule) {
        return "\"#%groovy% afterEach:\n" +
            "import com.github.mlytvyn.y2y.model.ExportPkMatrixModel\n" +
            "pkMatrix = modelService.create(ExportPkMatrixModel.class)\n" +
            "pkMatrix.setRulePK('" + exportRule.getPk().toString() + "')\n" +
            "pkMatrix.setSourcePK(line[1])\n" +
            "pkMatrix.setTargetPK(impex.lastImportedItem.PK.longValueAsString)\n" +
            "modelService.save(pkMatrix)\n" +
            "\";";
    }

    private long getCountForExportRule(final T cronJob, final ExportRuleModel exportRule, final String parentSeedPks) {
        final String countKey = "count";
        final String countQuery;
        if (exportRule.getCountQuery() != null && parentSeedPks != null) {
            if ("''".equals(parentSeedPks)) {
                throw new ExportException("No parent seed pks for rule: " + exportRule.getName());
            }
            countQuery = replaceQueryParams(cronJob, exportRule, exportRule.getCountQuery().replace("?pks", parentSeedPks));
        } else if (exportRule.getCountQuery() != null) {
            countQuery = replaceQueryParams(cronJob, exportRule, exportRule.getCountQuery());
        } else {
            // fallback to default count query
            countQuery = String.format("SELECT COUNT({PK}) AS \"%s\" FROM {%s}", countKey, exportRule.getTargetType().getCode());
        }
        final List<Map<String, String>> response = getExternalData(countQuery, exportRule, 1, cronJob.getSourceSystem());
        if (response.size() == 1) {
            final long externalCount = Long.parseLong(response.get(0).getOrDefault(countKey, "0"));
            final long maxCount = exportRule.getMaxCount();
            return maxCount == -1 ? externalCount : Math.min(maxCount, externalCount);
        } else {
            return 0;
        }
    }

    private List<Map<String, String>> getExternalData(final String requestQuery, final ExportRuleModel exportRule, final int maxRows,
                                                      final SourceSystemConfigurationModel sourceSystem) {
        final AtomicInteger retries = new AtomicInteger(0);
        while (retries.get() != exportRule.getRetries()) {
            final int retry = retries.incrementAndGet();
            try {
                return getData(requestQuery, maxRows, sourceSystem);
            } catch (final Exception e) {
                LOG.warn(new ParameterizedMessage("Failed to get data. Try {} of {}", retry, exportRule.getRetries()), e);
            }
        }
        throw new ExportException("Reached maximum amount of retries for Query:\n" + requestQuery);
    }

    private List<Map<String, String>> getData(final String requestQuery, final int maxRows, final SourceSystemConfigurationModel sourceSystem) {
        LOG.trace("--- Request ---\nURL: {}\nFSQ: {}", sourceSystem::getUrl, () -> requestQuery);

        return Y2YFlexibleSearch.getData(requestQuery, maxRows, sourceSystem.getUrl(), sourceSystem.getUsername(), sourceSystem.getPassword());
    }

    @Override
    public boolean isAbortable() {
        return true;
    }

    @Resource(name = "modelService")
    @Override
    public void setModelService(final ModelService modelService) {
        super.setModelService(modelService);
    }

    @Resource(name = "sessionService")
    @Override
    public void setSessionService(final SessionService sessionService) {
        super.setSessionService(sessionService);
    }

    @Resource(name = "flexibleSearchService")
    @Override
    public void setFlexibleSearchService(final FlexibleSearchService flexibleSearchService) {
        super.setFlexibleSearchService(flexibleSearchService);
    }
}
