package cloud.dopp.homes;

import android.Manifest;
import android.accounts.Account;
import android.app.Application;
import android.content.ContentResolver;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import java.util.Arrays;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowContentResolver;

/**
 * Robolectric's ContentResolver, but asking for a sync's state as Android's
 * ContentService does: only with READ_SYNC_STATS, else a SecurityException.
 * A normal permission, so granted exactly when the manifest asks for it
 * (HomesKontoJetztTest). Java, since a Kotlin shadow cannot hide the static
 * methods it overrides.
 */
@Implements(ContentResolver.class)
public class PruefendeSyncs extends ShadowContentResolver {

    private static void pruefe() {
        Application app = RuntimeEnvironment.getApplication();
        String[] verlangt;
        try {
            PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), PackageManager.GET_PERMISSIONS);
            verlangt = info.requestedPermissions == null ? new String[0] : info.requestedPermissions;
        } catch (PackageManager.NameNotFoundException e) {
            throw new AssertionError(e);
        }
        if (!Arrays.asList(verlangt).contains(Manifest.permission.READ_SYNC_STATS)) {
            throw new SecurityException("no permission to read the sync stats");
        }
    }

    @Implementation
    protected static boolean isSyncActive(Account account, String authority) {
        pruefe();
        return ShadowContentResolver.isSyncActive(account, authority);
    }

    @Implementation
    protected static boolean isSyncPending(Account account, String authority) {
        pruefe();
        return false;
    }
}
