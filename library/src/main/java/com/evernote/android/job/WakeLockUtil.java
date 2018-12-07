/*
 * Copyright (C) 2018 Evernote Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.evernote.android.job;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RestrictTo;
import android.util.SparseArray;

import com.evernote.android.job.util.JobCat;
import com.evernote.android.job.util.JobUtil;

import java.util.concurrent.TimeUnit;

/**
 * @author rwondratschek
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
/*package*/ final class WakeLockUtil {

    private static final JobCat CAT = new JobCat("WakeLockUtil");

    private static final String EXTRA_WAKE_LOCK_ID = "com.evernote.android.job.wakelockid";

    private WakeLockUtil() {
        // no op
    }

    @SuppressWarnings("SameParameterValue")
    @Nullable
    static PowerManager.WakeLock acquireWakeLock(@NonNull Context context, @NonNull String tag, long timeoutMillis) {
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);

        PowerManager.WakeLock wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag);
        wakeLock.setReferenceCounted(false);

        return acquireWakeLock(context, wakeLock, timeoutMillis) ? wakeLock : null;
    }

    static boolean acquireWakeLock(@NonNull Context context, @Nullable PowerManager.WakeLock wakeLock, long timeoutMillis) {
        if (wakeLock != null && !wakeLock.isHeld() && JobUtil.hasWakeLockPermission(context)) {
            // Even if we have the permission, some devices throw an exception in the try block nonetheless,
            // I'm looking at you, Samsung SM-T805

            try {
                wakeLock.acquire(timeoutMillis);
                return true;
            } catch (Exception e) {
                // saw an NPE on rooted Galaxy Nexus Android 4.1.1
                // android.os.IPowerManager$Stub$Proxy.acquireWakeLock(IPowerManager.java:288)
                CAT.e(e);
            }
        }
        return false;
    }

    static void releaseWakeLock(@Nullable PowerManager.WakeLock wakeLock) {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception e) {
            // just to make sure if the PowerManager crashes while acquiring a wake lock
            CAT.e(e);
        }
    }

    private static final SparseArray<PowerManager.WakeLock> ACTIVE_WAKE_LOCKS = new SparseArray<>();
    private static int nextId = 1;

    /**
     * Do a {@link android.content.Context#startService(android.content.Intent)
     * Context.startService}, but holding a wake lock while the service starts.
     * This will modify the Intent to hold an extra identifying the wake lock;
     * when the service receives it in {@link android.app.Service#onStartCommand
     * Service.onStartCommand}, it should pass back the Intent it receives there to
     * {@link #completeWakefulIntent(android.content.Intent)} in order to release
     * the wake lock.
     *
     * @param context The Context in which it operate.
     * @param intent The Intent with which to start the service, as per
     * {@link android.content.Context#startService(android.content.Intent)
     * Context.startService}.
     */
    public static ComponentName startWakefulService(Context context, Intent intent) {
        synchronized (ACTIVE_WAKE_LOCKS) {
            int id = nextId;
            nextId++;
            if (nextId <= 0) {
                nextId = 1;
            }

            intent.putExtra(EXTRA_WAKE_LOCK_ID, id);

            try {
                ComponentName comp = context.startService(intent);
                if (comp == null) {
                    return null;
                }

                String tag = "wake:" + comp.flattenToShortString();
                PowerManager.WakeLock wakeLock = acquireWakeLock(context, tag, TimeUnit.MINUTES.toMillis(3));
                if (wakeLock != null) {
                    ACTIVE_WAKE_LOCKS.put(id, wakeLock);
                }

                return comp;

            } catch (Throwable throwable) {
                CAT.e(throwable);
                return null;
            }
        }
    }

    /**
     * Finish the execution from a previous {@link #startWakefulService}.  Any wake lock
     * that was being held will now be released.
     *
     * @param intent The Intent as originally generated by {@link #startWakefulService}.
     * @return Returns true if the intent is associated with a wake lock that is
     * now released; returns false if there was no wake lock specified for it.
     */
    public static boolean completeWakefulIntent(Intent intent) {
        if (intent == null) {
            return false;
        }

        final int id = intent.getIntExtra(EXTRA_WAKE_LOCK_ID, 0);
        if (id == 0) {
            return false;
        }
        synchronized (ACTIVE_WAKE_LOCKS) {
            releaseWakeLock(ACTIVE_WAKE_LOCKS.get(id));
            ACTIVE_WAKE_LOCKS.remove(id);
            return true;
        }
    }
}
