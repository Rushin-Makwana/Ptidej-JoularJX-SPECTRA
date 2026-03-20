/*
 * Copyright (c) 2021-2026, Adel Noureddine, Université de Pau et des Pays de l'Adour.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the
 * GNU General Public License v3.0 only (GPL-3.0-only)
 * which accompanies this distribution, and is available at
 * https://www.gnu.org/licenses/gpl-3.0.en.html
 *
 */

package org.noureddine.joularjx.monitor;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.noureddine.joularjx.Agent;
import org.noureddine.joularjx.cpu.Cpu;
import org.noureddine.joularjx.result.ResultScope;
import org.noureddine.joularjx.result.ResultWriter;
import org.noureddine.joularjx.result.ResultWriterConfiguration;
import org.noureddine.joularjx.utils.AgentProperties;
import org.noureddine.joularjx.utils.JoularJXLogging;

/**
 * The ShutdownHandler is meant to be called at the end of the agent and is
 * responsible for displaying and writing all the consumption data in dedicated
 * files. It also performs resource closing operations.
 */
public class ShutdownHandler implements Runnable {

    private static final Logger logger = JoularJXLogging.getLogger();

    private final long appPid;
    private final List<ResultWriter> resultWriters;
    private final Cpu cpu;
    private final MonitoringStatus status;
    private final AgentProperties properties;

    /**
     * Creates a new ShutdownHandler.
     *
     * @param appPid        the PID of the monitored application
     * @param resultWriters the writer that will be used to save data in files
     * @param cpu           an implementation of the CPU interface, depending on the
     *                      OS and hardware
     * @param status        where all the runtime data will be saved
     * @param properties    the agent's configuration properties
     */
    public ShutdownHandler(long appPid, List<ResultWriter> resultWriters, Cpu cpu, MonitoringStatus status,
                           AgentProperties properties) {
        this.appPid = appPid;
        this.resultWriters = resultWriters;
        this.cpu = cpu;
        this.status = status;
        this.properties = properties;
    }

    @Override
    public void run() {
        // Close monitoring implementation to release all resources
        try {
            cpu.close();
        } catch (final Exception exception) {
            // Continue shutting down
        }

        logger.log(Level.INFO, String.format("JoularJX finished monitoring application with ID %d", appPid));
        logger.log(Level.INFO, "Program consumed {0,number,#.##} joules", status.getTotalConsumedEnergy());
        try {
            // Writing methods and filtered methods energy consumption
            this.saveResults(status.getMethodsConsumedEnergy(), status.getSamplingStats(),
                    new ResultWriterConfiguration(ResultScope.ALL_TOTAL_METHODS));
            this.saveResults(status.getFilteredMethodsConsumedEnergy(), status.getSamplingStats(),
                    new ResultWriterConfiguration(ResultScope.FILTERED_TOTAL_METHODS));

            // Writing consumption evolution files only if the option is enabled
            if (this.properties.trackConsumptionEvolution()) {
                // All methods
                for (final var methodEntry : this.status.getMethodsConsumptionEvolution().entrySet()) {
                    this.saveResults(methodEntry.getValue(), status.getSamplingStats(), new ResultWriterConfiguration(ResultScope.ALL_EVOLUTION,
                            sanitizeMethodName(methodEntry.getKey())));
                }

                // Filtered methods
                for (final var methodEntry : this.status.getFilteredMethodsConsumptionEvolution().entrySet()) {
                    this.saveResults(methodEntry.getValue(), status.getSamplingStats(), new ResultWriterConfiguration(
                            ResultScope.FILTERED_EVOLUTION, sanitizeMethodName(methodEntry.getKey())));
                }
            }

            // Writing call trees consumption file only if the option is enabled
            if (this.properties.callTreesConsumption()) {
                this.saveResults(status.getCallTreesConsumedEnergy(), status.getSamplingStats(),
                        new ResultWriterConfiguration(ResultScope.ALL_TOTAL_CALL_TREE));
                this.saveResults(status.getFilteredCallTreesConsumedEnergy(),status.getSamplingStats(),
                        new ResultWriterConfiguration(ResultScope.FILTERED_TOTAL_CALL_TREE));
            }
        } catch (final IOException exception) {
            // Continue shutting down
        }

        logger.log(Level.INFO, "Energy consumption of methods and filtered methods written to files");
    }

    /**
     * Cleans a method name to make it suitable for inclusion in a filename
     *
     * @param methodName the method name
     * @return the sanitized version
     */
    private String sanitizeMethodName(String methodName) {
        return methodName.replaceAll("[<>]", "_");
    }

    /**
     * Writes the result. The filename is partially defined by the given
     * parameters.
     *
     * This overload is used everywhere in run(). It automatically wires in
     * sampling information (if available) from MonitoringStatus.
     *
     * @param consumedEnergyMap the data to be written.
     * @param config            configuration for the writers
     * @throws IOException if an I/O error occurs while writing the data.
     */
    public void saveResults(Map<?, Double> consumedEnergyMap,  Map<String, MethodStats> samplingStats, ResultWriterConfiguration config) throws IOException {
        // Sampling stats (may be null if sampling was disabled)
//         samplingStats = status.getSamplingStats();
        long samplingPeriodMs = properties.stackMonitoringSampleRate();
        logger.info("samplingStats size in saveResults = " +
                (samplingStats == null ? "null" : samplingStats.size()));
        saveResults(consumedEnergyMap, config, samplingStats, samplingPeriodMs);
    }

    /**
     * Internal helper that actually writes the file and appends
     * EstInvocations / TopSamples / EstSelfTimeMs when sampling data is present.
     *
     * @param consumedEnergyMap the data to be written.
     * @param config            configuration for the writers
     * @param samplingStats     per-method sampling stats (may be null)
     * @param samplingPeriodMs  sampling rate in milliseconds
     * @throws IOException if an I/O error occurs while writing the data.
     */
    private void saveResults(Map<?, Double> consumedEnergyMap,
                             ResultWriterConfiguration config,
                             Map<String, MethodStats> samplingStats,
                             long samplingPeriodMs) throws IOException {

        // String fileName = String.format("joularJX-%d-%s-%s", appPid, nodeType,
        // dataType);
        logger.info(String.format("Saving results for config %s", config.toString()));
        for (final ResultWriter resultWriter : resultWriters) {
            resultWriter.setConfiguration(config);
        }

        // Precompute maxEnergy for proportional fallback scaling
        double maxEnergy = consumedEnergyMap.values().stream()
                .mapToDouble(Double::doubleValue).max().orElse(1.0);
        long totalEnergyEntries = consumedEnergyMap.size();

        for (final var entry : consumedEnergyMap.entrySet()) {
            String methodName = entry.getKey().toString();
            double energy = entry.getValue();

            // Look up sampling stats for this method (may be null)
            // Fallback when samplingStats is missing or method not sampled:
            long topSamples = 0L;
            long sampleCount = 0L;

            if (samplingStats != null) {
                MethodStats ms =  samplingStats.get(methodName);
                if (ms != null) {
                    topSamples = ms.topSamples;
                    sampleCount = ms.sampleCount;
                    energy = ms.energyJ; // Override energy with sampled energy for this method
                }
            }

            // Derived metrics with sensible fallbacks
            double estInvocations;
            double selfTimeMs;
            double totalTimeMs;
            logger.info("Using samplingPeriodMs=" + samplingPeriodMs + " from config");
//            if (topSamples > 0 && sampleCount > 0) {
                estInvocations =   (double) sampleCount ;
                selfTimeMs     = topSamples * samplingPeriodMs;
                totalTimeMs    = sampleCount * samplingPeriodMs;
//
//            }  else {
//                // Energy-proportional fallback: scale by relative energy
//                // Methods with energy >10% of max get 1-10 estInvocations
//                logger.info("fallback");
//                double relEnergy = energy / maxEnergy;
//                estInvocations = Math.max(1.0, relEnergy * 10.0);
//                selfTimeMs = samplingPeriodMs * estInvocations;
//                totalTimeMs = samplingPeriodMs * (1.0 + relEnergy * 5.0);  // Slightly more total time
//            }

            for (final ResultWriter resultWriter : resultWriters) {
                resultWriter.write("\"" + methodName.replace("\"", "\"\"") + "\"," ,
                        energy ,estInvocations ,  selfTimeMs , totalTimeMs);

            }

        }

        for (final ResultWriter resultWriter : resultWriters) {
            resultWriter.closeTarget();
        }
    }
}
