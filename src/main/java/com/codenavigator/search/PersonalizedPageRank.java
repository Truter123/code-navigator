package com.codenavigator.search;

import java.util.HashMap;

/**
 * Personalised PageRank over an undirected graph held in primitive arrays. Pure: no store, so it
 * is testable on a hand-made graph. Parallel edges between one pair collapse to a single edge of
 * weight {@code sqrt(count)}, so four calls between two places weigh 2, not 4. Mass that reaches
 * a node without edges returns to the personalisation vector. A fixed iteration count, no
 * convergence loop: the cost of a call is known in advance.
 */
final class PersonalizedPageRank {

    static final double DAMPING = 0.85;
    static final int ITERATIONS = 20;

    private PersonalizedPageRank() {}

    /**
     * @param n               node count; nodes are 0..n-1
     * @param from            edge endpoints, one entry per edge (parallel edges repeat)
     * @param to              the other endpoints, same length as {@code from}
     * @param personalisation non-negative mass per node, normalised here; all zero gives all zero
     * @return one score per node, summing to 1 (or all 0 without personalisation mass)
     */
    static double[] rank(int n, int[] from, int[] to, double[] personalisation) {
        double[] p = new double[n];
        double total = 0;
        for (double v : personalisation) total += v;
        if (total <= 0) return p;
        for (int i = 0; i < n; i++) p[i] = personalisation[i] / total;

        // Collapse parallel edges; a self-loop carries no rank to anywhere else.
        var counts = new HashMap<Long, Integer>();
        for (int i = 0; i < from.length; i++) {
            int a = Math.min(from[i], to[i]);
            int b = Math.max(from[i], to[i]);
            if (a == b) continue;
            counts.merge((long) a * n + b, 1, Integer::sum);
        }
        int m = counts.size();
        int[] ea = new int[m];
        int[] eb = new int[m];
        double[] ew = new double[m];
        double[] strength = new double[n];
        int k = 0;
        for (var entry : counts.entrySet()) {
            ea[k] = (int) (entry.getKey() / n);
            eb[k] = (int) (entry.getKey() % n);
            ew[k] = Math.sqrt(entry.getValue());
            strength[ea[k]] += ew[k];
            strength[eb[k]] += ew[k];
            k++;
        }

        double[] r = p.clone();
        double[] next = new double[n];
        for (int iter = 0; iter < ITERATIONS; iter++) {
            double dangling = 0;
            for (int i = 0; i < n; i++) if (strength[i] == 0) dangling += r[i];
            double base = (1 - DAMPING) + DAMPING * dangling;
            for (int i = 0; i < n; i++) next[i] = base * p[i];
            for (int e = 0; e < m; e++) {
                next[eb[e]] += DAMPING * r[ea[e]] * ew[e] / strength[ea[e]];
                next[ea[e]] += DAMPING * r[eb[e]] * ew[e] / strength[eb[e]];
            }
            double[] swap = r;
            r = next;
            next = swap;
        }
        return r;
    }
}
