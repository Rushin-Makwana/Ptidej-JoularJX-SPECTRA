package org.noureddine.joularjx;

import java.sql.SQLOutput;

public class TestWorkload {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== TestWorkload STARTED - Press Ctrl+C to stop ===");

        long start = System.currentTimeMillis();


       for (int i = 0; i < 10; i++) {  // Run until Ctrl+C
            compute(i);
            TestWorkload workload = new TestWorkload();
            workload.computeB(i);
                long elapsed = (System.currentTimeMillis() - start) / 1000;
                System.out.printf("Iterations: %d, Elapsed: %ds%n", i, elapsed);
                Thread.sleep(10);  // Small pause for readability
        }
    }

    public static void compute(int n) throws InterruptedException {
        double sum = 0;
        for (int j = 0; j < 1000; j++) {
            sum += Math.sqrt(n * j + 1);
            Thread.sleep(10);
        }
        // Force some computation
    }
    private void computeB(int n) throws InterruptedException {
        double sum = 0;
        for (int j = 0; j < 1000; j++) {
            sum += Math.sqrt(n * j + 1);
            System.out.println("computeB: " + sum + " iteration: " + j);
            Thread.sleep(10);

        }

    }
}

