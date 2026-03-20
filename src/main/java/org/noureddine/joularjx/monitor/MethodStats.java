package org.noureddine.joularjx.monitor;

public final class MethodStats {
    public long sampleCount;     // any position - total time proxy
    public long topSamples;      // top frame - self time + invocation proxy
    public double energyJ;

    public MethodStats() {
        this.sampleCount = 0;
        this.topSamples = 0;
        this.energyJ = 0.0;
    }
    public void addSample(boolean isTop, double energyShareJ) {
        sampleCount++;
        energyJ += energyShareJ;
        if (isTop) {
            topSamples++;
        }
    }
    public void addTopSample(double ignoredEnergyShare) {
        topSamples++;
        energyJ += ignoredEnergyShare;
    }
}
