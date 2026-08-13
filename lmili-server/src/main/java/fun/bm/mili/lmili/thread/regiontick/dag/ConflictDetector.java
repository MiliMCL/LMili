package fun.bm.mili.lmili.thread.regiontick.dag;

import org.jetbrains.annotations.NotNull;

public final class ConflictDetector {

    private ConflictDetector() {}

    public static boolean isConflicting(final @NotNull SystemProfile aProfile,
                                         final @NotNull Scope aScope,
                                         final @NotNull SystemProfile bProfile,
                                         final @NotNull Scope bScope) {
        if (!aProfile.typeIntersects(bProfile)) return false;
        if (!aProfile.hasWriteConflictWith(bProfile)) return false;
        if (aScope.isGlobal() || bScope.isGlobal()) return true;
        return aScope.intersects(bScope);
    }

    public static boolean[][] buildConflictMatrix(final SystemProfile @NotNull [] profiles,
                                                   final Scope @NotNull [] scopes) {
        int n = profiles.length;
        boolean[][] matrix = new boolean[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                boolean conflicts = isConflicting(profiles[i], scopes[i], profiles[j], scopes[j]);
                matrix[i][j] = conflicts;
                matrix[j][i] = conflicts;
            }
        }
        return matrix;
    }
}
