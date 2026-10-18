package com.particlesdevs.photoncamera.util;

/** The package-private pieces of {@link ScameraFolder} for tests in other packages. */
public final class ScameraFolderAccess {
    private ScameraFolderAccess() {
    }

    public static String primaryId(String authority, String treeId) {
        return ScameraFolder.primaryId(authority, treeId);
    }

    public static String idUnder(ScameraFolder folder, String authority) {
        return folder.documentIdFor(authority);
    }
}
