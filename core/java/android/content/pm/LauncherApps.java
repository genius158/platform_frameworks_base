/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.content.pm;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager.ApplicationInfoFlags;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Class for retrieving a list of launchable activities for the current user and any associated
 * managed profiles. This is mainly for use by launchers. Apps can be queried for each user profile.
 * Since the PackageManager will not deliver package broadcasts for other profiles, you can register
 * for package changes here.
 * <p>
 * To watch for managed profiles being added or removed, register for the following broadcasts:
 * {@link Intent#ACTION_MANAGED_PROFILE_ADDED} and {@link Intent#ACTION_MANAGED_PROFILE_REMOVED}.
 * <p>
 * You can retrieve the list of profiles associated with this user with
 * {@link UserManager#getUserProfiles()}.
 */
public class LauncherApps {

    static final String TAG = "LauncherApps";
    static final boolean DEBUG = false;

    private Context mContext;
    private ILauncherApps mService;
    private PackageManager mPm;

    private List<CallbackMessageHandler> mCallbacks
            = new ArrayList<CallbackMessageHandler>();

    /**
     * Callbacks for package changes to this and related managed profiles.
     */
    public static abstract class Callback {
        /**
         * Indicates that a package was removed from the specified profile.
         *
         * If a package is removed while being updated onPackageChanged will be
         * called instead.
         *
         * @param packageName The name of the package that was removed.
         * @param user The UserHandle of the profile that generated the change.
         */
        abstract public void onPackageRemoved(String packageName, UserHandle user);

        /**
         * Indicates that a package was added to the specified profile.
         *
         * If a package is added while being updated then onPackageChanged will be
         * called instead.
         *
         * @param packageName The name of the package that was added.
         * @param user The UserHandle of the profile that generated the change.
         */
        abstract public void onPackageAdded(String packageName, UserHandle user);

        /**
         * Indicates that a package was modified in the specified profile.
         * This can happen, for example, when the package is updated or when
         * one or more components are enabled or disabled.
         *
         * @param packageName The name of the package that has changed.
         * @param user The UserHandle of the profile that generated the change.
         */
        abstract public void onPackageChanged(String packageName, UserHandle user);

        /**
         * Indicates that one or more packages have become available. For
         * example, this can happen when a removable storage card has
         * reappeared.
         *
         * @param packageNames The names of the packages that have become
         *            available.
         * @param user The UserHandle of the profile that generated the change.
         * @param replacing Indicates whether these packages are replacing
         *            existing ones.
         */
        abstract public void onPackagesAvailable(String[] packageNames, UserHandle user,
                boolean replacing);

        /**
         * Indicates that one or more packages have become unavailable. For
         * example, this can happen when a removable storage card has been
         * removed.
         *
         * @param packageNames The names of the packages that have become
         *            unavailable.
         * @param user The UserHandle of the profile that generated the change.
         * @param replacing Indicates whether the packages are about to be
         *            replaced with new versions.
         */
        abstract public void onPackagesUnavailable(String[] packageNames, UserHandle user,
                boolean replacing);

        /**
         * Indicates that one or more packages have been suspended. For
         * example, this can happen when a Device Administrator suspends
         * an applicaton.
         *
         * @param packageNames The names of the packages that have just been
         *            suspended.
         * @param user The UserHandle of the profile that generated the change.
         */
        public void onPackagesSuspended(String[] packageNames, UserHandle user) {
        }

        /**
         * Indicates that one or more packages have been unsuspended. For
         * example, this can happen when a Device Administrator unsuspends
         * an applicaton.
         *
         * @param packageNames The names of the packages that have just been
         *            unsuspended.
         * @param user The UserHandle of the profile that generated the change.
         */
        public void onPackagesUnsuspended(String[] packageNames, UserHandle user) {
        }

        /**
         * Indicates that one or more shortcuts (which may be dynamic and/or pinned)
         * have been added, updated or removed.
         *
         * <p>Only the applications that are allowed to access the shortcut information,
         * as defined in {@link #hasShortcutHostPermission()}, will receive it.
         *
         * @param packageName The name of the package that has the shortcuts.
         * @param shortcuts all shortcuts from the package (dynamic and/or pinned).  Only "key"
         *    information will be provided, as defined in {@link ShortcutInfo#hasKeyFieldsOnly()}.
         * @param user The UserHandle of the profile that generated the change.
         *
         * @hide
         */
        public void onShortcutsChanged(@NonNull String packageName,
                @NonNull List<ShortcutInfo> shortcuts, @NonNull UserHandle user) {
        }
    }

    /**
     * Represents a query passed to {@link #getShortcuts(ShortcutQuery, UserHandle)}.
     *
     * @hide
     */
    public static class ShortcutQuery {
        /**
         * Include dynamic shortcuts in the result.
         */
        public static final int FLAG_GET_DYNAMIC = 1 << 0;

        /**
         * Include pinned shortcuts in the result.
         */
        public static final int FLAG_GET_PINNED = 1 << 1;

        /**
         * Requests "key" fields only.  See {@link ShortcutInfo#hasKeyFieldsOnly()} for which
         * fields are available.
         */
        public static final int FLAG_GET_KEY_FIELDS_ONLY = 1 << 2;

        /** @hide */
        @IntDef(flag = true,
                value = {
                        FLAG_GET_DYNAMIC,
                        FLAG_GET_PINNED,
                        FLAG_GET_KEY_FIELDS_ONLY,
                })
        @Retention(RetentionPolicy.SOURCE)
        public @interface QueryFlags {}

        long mChangedSince;

        @Nullable
        String mPackage;

        @Nullable
        List<String> mShortcutIds;

        @Nullable
        ComponentName mActivity;

        @QueryFlags
        int mQueryFlags;

        public ShortcutQuery() {
        }

        /**
         * If non-zero, returns only shortcuts that have been added or updated since the timestamp,
         * which is a milliseconds since the Epoch.
         */
        public void setChangedSince(long changedSince) {
            mChangedSince = changedSince;
        }

        /**
         * If non-null, returns only shortcuts from the package.
         */
        public void setPackage(@Nullable String packageName) {
            mPackage = packageName;
        }

        /**
         * If non-null, return only the specified shortcuts by ID.  When setting this field,
         * a packange name must also be set with {@link #setPackage}.
         */
        public void setShortcutIds(@Nullable List<String> shortcutIds) {
            mShortcutIds = shortcutIds;
        }

        /**
         * If non-null, returns only shortcuts associated with the activity.
         */
        public void setActivity(@Nullable ComponentName activity) {
            mActivity = activity;
        }

        /**
         * Set query options.
         */
        public void setQueryFlags(@QueryFlags int queryFlags) {
            mQueryFlags = queryFlags;
        }
    }

    /** @hide */
    public LauncherApps(Context context, ILauncherApps service) {
        mContext = context;
        mService = service;
        mPm = context.getPackageManager();
    }

    /** @hide */
    @TestApi
    public LauncherApps(Context context) {
        this(context, ILauncherApps.Stub.asInterface(
                ServiceManager.getService(Context.LAUNCHER_APPS_SERVICE)));
    }

    /**
     * Retrieves a list of launchable activities that match {@link Intent#ACTION_MAIN} and
     * {@link Intent#CATEGORY_LAUNCHER}, for a specified user.
     *
     * @param packageName The specific package to query. If null, it checks all installed packages
     *            in the profile.
     * @param user The UserHandle of the profile.
     * @return List of launchable activities. Can be an empty list but will not be null.
     */
    public List<LauncherActivityInfo> getActivityList(String packageName, UserHandle user) {
        ParceledListSlice<ResolveInfo> activities = null;
        try {
            activities = mService.getLauncherActivities(packageName, user);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
        if (activities == null) {
            return Collections.EMPTY_LIST;
        }
        ArrayList<LauncherActivityInfo> lais = new ArrayList<LauncherActivityInfo>();
        for (ResolveInfo ri : activities.getList()) {
            LauncherActivityInfo lai = new LauncherActivityInfo(mContext, ri.activityInfo, user);
            if (DEBUG) {
                Log.v(TAG, "Returning activity for profile " + user + " : "
                        + lai.getComponentName());
            }
            lais.add(lai);
        }
        return lais;
    }

    /**
     * Returns the activity info for a given intent and user handle, if it resolves. Otherwise it
     * returns null.
     *
     * @param intent The intent to find a match for.
     * @param user The profile to look in for a match.
     * @return An activity info object if there is a match.
     */
    public LauncherActivityInfo resolveActivity(Intent intent, UserHandle user) {
        try {
            ActivityInfo ai = mService.resolveActivity(intent.getComponent(), user);
            if (ai != null) {
                LauncherActivityInfo info = new LauncherActivityInfo(mContext, ai, user);
                return info;
            }
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
        return null;
    }

    /**
     * Starts a Main activity in the specified profile.
     *
     * @param component The ComponentName of the activity to launch
     * @param user The UserHandle of the profile
     * @param sourceBounds The Rect containing the source bounds of the clicked icon
     * @param opts Options to pass to startActivity
     */
    public void startMainActivity(ComponentName component, UserHandle user, Rect sourceBounds,
            Bundle opts) {
        if (DEBUG) {
            Log.i(TAG, "StartMainActivity " + component + " " + user.getIdentifier());
        }
        try {
            mService.startActivityAsUser(component, sourceBounds, opts, user);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Starts the settings activity to show the application details for a
     * package in the specified profile.
     *
     * @param component The ComponentName of the package to launch settings for.
     * @param user The UserHandle of the profile
     * @param sourceBounds The Rect containing the source bounds of the clicked icon
     * @param opts Options to pass to startActivity
     */
    public void startAppDetailsActivity(ComponentName component, UserHandle user,
            Rect sourceBounds, Bundle opts) {
        try {
            mService.showAppDetailsAsUser(component, sourceBounds, opts, user);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Checks if the package is installed and enabled for a profile.
     *
     * @param packageName The package to check.
     * @param user The UserHandle of the profile.
     *
     * @return true if the package exists and is enabled.
     */
    public boolean isPackageEnabled(String packageName, UserHandle user) {
        try {
            return mService.isPackageEnabled(packageName, user);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieve all of the information we know about a particular package / application.
     *
     * @param packageName The package of the application
     * @param flags Additional option flags {@link PackageManager#getApplicationInfo}
     * @param user The UserHandle of the profile.
     *
     * @return An {@link ApplicationInfo} containing information about the package or
     *         null of the package isn't found.
     * @hide
     */
    public ApplicationInfo getApplicationInfo(String packageName, @ApplicationInfoFlags int flags,
            UserHandle user) {
        try {
            return mService.getApplicationInfo(packageName, flags, user);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Checks if the activity exists and it enabled for a profile.
     *
     * @param component The activity to check.
     * @param user The UserHandle of the profile.
     *
     * @return true if the activity exists and is enabled.
     */
    public boolean isActivityEnabled(ComponentName component, UserHandle user) {
        try {
            return mService.isActivityEnabled(component, user);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether the caller can access the shortcut information.
     *
     * <p>Only the default launcher can access the shortcut information.
     *
     * <p>Note when this method returns {@code false}, that may be a temporary situation because
     * the user is trying a new launcher application.  The user may decide to change the default
     * launcher to the calling application again, so even if a launcher application loses
     * this permission, it does <b>not</b> have to purge pinned shortcut information.
     *
     * @hide
     */
    public boolean hasShortcutHostPermission() {
        try {
            return mService.hasShortcutHostPermission(mContext.getPackageName());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the IDs of {@link ShortcutInfo}s that match {@code query}.
     *
     * <p>Callers must be allowed to access the shortcut information, as defined in {@link
     * #hasShortcutHostPermission()}.
     *
     * @param query result includes shortcuts matching this query.
     * @param user The UserHandle of the profile.
     *
     * @return the IDs of {@link ShortcutInfo}s that match the query.
     *
     * @hide
     */
    @Nullable
    public List<ShortcutInfo> getShortcuts(@NonNull ShortcutQuery query,
            @NonNull UserHandle user) {
        try {
            return mService.getShortcuts(mContext.getPackageName(),
                    query.mChangedSince, query.mPackage, query.mShortcutIds, query.mActivity,
                    query.mQueryFlags, user)
                    .getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide // No longer used.  Use getShortcuts() instead.  Kept for unit tests.
     */
    @Nullable
    public List<ShortcutInfo> getShortcutInfo(@NonNull String packageName,
            @NonNull List<String> ids, @NonNull UserHandle user) {
        final ShortcutQuery q = new ShortcutQuery();
        q.setPackage(packageName);
        q.setShortcutIds(ids);
        q.setQueryFlags(ShortcutQuery.FLAG_GET_DYNAMIC | ShortcutQuery.FLAG_GET_PINNED);
        return getShortcuts(q, user);
    }

    /**
     * Pin shortcuts on a package.
     *
     * <p>This API is <b>NOT</b> cumulative; this will replace all pinned shortcuts for the package.
     * However, different launchers may have different set of pinned shortcuts.
     *
     * <p>Callers must be allowed to access the shortcut information, as defined in {@link
     * #hasShortcutHostPermission()}.
     *
     * @param packageName The target package name.
     * @param shortcutIds The IDs of the shortcut to be pinned.
     * @param user The UserHandle of the profile.
     *
     * @hide
     */
    public void pinShortcuts(@NonNull String packageName, @NonNull List<String> shortcutIds,
            @NonNull UserHandle user) {
        try {
            mService.pinShortcuts(mContext.getPackageName(), packageName, shortcutIds, user);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide kept for testing.
     */
    public int getShortcutIconResId(@NonNull ShortcutInfo shortcut) {
        return shortcut.getIconResourceId();
    }

    /**
     * @hide kept for testing.
     */
    public int getShortcutIconResId(@NonNull String packageName, @NonNull String shortcutId,
            @NonNull UserHandle user) {
        final ShortcutQuery q = new ShortcutQuery();
        q.setPackage(packageName);
        q.setShortcutIds(Arrays.asList(shortcutId));
        q.setQueryFlags(ShortcutQuery.FLAG_GET_DYNAMIC | ShortcutQuery.FLAG_GET_PINNED);
        final List<ShortcutInfo> shortcuts = getShortcuts(q, user);

        return shortcuts.size() > 0 ? shortcuts.get(0).getIconResourceId() : 0;
    }

    /**
     * Return the icon as {@link ParcelFileDescriptor}, when it's stored as a file
     * (i.e. when {@link ShortcutInfo#hasIconFile()} returns {@code true}).
     *
     * <p>Callers must be allowed to access the shortcut information, as defined in {@link
     * #hasShortcutHostPermission()}.
     *
     * @param shortcut The target shortcut.
     *
     * @hide
     */
    public ParcelFileDescriptor getShortcutIconFd(
            @NonNull ShortcutInfo shortcut) {
        return getShortcutIconFd(shortcut.getPackageName(), shortcut.getId(),
                shortcut.getUserId());
    }

    /**
     * Return the icon as {@link ParcelFileDescriptor}, when it's stored as a file
     * (i.e. when {@link ShortcutInfo#hasIconFile()} returns {@code true}).
     *
     * <p>Callers must be allowed to access the shortcut information, as defined in {@link
     * #hasShortcutHostPermission()}.
     *
     * @param packageName The target package name.
     * @param shortcutId The ID of the shortcut to lad rom.
     * @param user The UserHandle of the profile.
     *
     * @hide
     */
    public ParcelFileDescriptor getShortcutIconFd(
            @NonNull String packageName, @NonNull String shortcutId, @NonNull UserHandle user) {
        return getShortcutIconFd(packageName, shortcutId, user.getIdentifier());
    }

    private ParcelFileDescriptor getShortcutIconFd(
            @NonNull String packageName, @NonNull String shortcutId, int userId) {
        try {
            return mService.getShortcutIconFd(mContext.getPackageName(),
                    packageName, shortcutId, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Launches a shortcut.
     *
     * <p>Callers must be allowed to access the shortcut information, as defined in {@link
     * #hasShortcutHostPermission()}.
     *
     * @param packageName The target shortcut package name.
     * @param shortcutId The target shortcut ID.
     * @param sourceBounds The Rect containing the source bounds of the clicked icon.
     * @param startActivityOptions Options to pass to startActivity.
     * @param user The UserHandle of the profile.
     * @return {@code false} when the shortcut is no longer valid (e.g. the creator application
     *   has been uninstalled). {@code true} when the shortcut is still valid.
     *
     * @hide
     */
    public boolean startShortcut(@NonNull String packageName, @NonNull String shortcutId,
            @Nullable Rect sourceBounds, @Nullable Bundle startActivityOptions,
            @NonNull UserHandle user) {
        return startShortcut(packageName, shortcutId, sourceBounds, startActivityOptions,
                user.getIdentifier());
    }

    /**
     * Launches a shortcut.
     *
     * <p>Callers must be allowed to access the shortcut information, as defined in {@link
     * #hasShortcutHostPermission()}.
     *
     * @param shortcut The target shortcut.
     * @param sourceBounds The Rect containing the source bounds of the clicked icon.
     * @param startActivityOptions Options to pass to startActivity.
     * @return {@code false} when the shortcut is no longer valid (e.g. the creator application
     *   has been uninstalled). {@code true} when the shortcut is still valid.
     *
     * @hide
     */
    public boolean startShortcut(@NonNull ShortcutInfo shortcut,
            @Nullable Rect sourceBounds, @Nullable Bundle startActivityOptions) {
        return startShortcut(shortcut.getPackageName(), shortcut.getId(),
                sourceBounds, startActivityOptions,
                shortcut.getUserId());
    }

    private boolean startShortcut(@NonNull String packageName, @NonNull String shortcutId,
            @Nullable Rect sourceBounds, @Nullable Bundle startActivityOptions,
            int userId) {
        try {
            return mService.startShortcut(mContext.getPackageName(), packageName, shortcutId,
                    sourceBounds, startActivityOptions, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers a callback for changes to packages in current and managed profiles.
     *
     * @param callback The callback to register.
     */
    public void registerCallback(Callback callback) {
        registerCallback(callback, null);
    }

    /**
     * Registers a callback for changes to packages in current and managed profiles.
     *
     * @param callback The callback to register.
     * @param handler that should be used to post callbacks on, may be null.
     */
    public void registerCallback(Callback callback, Handler handler) {
        synchronized (this) {
            if (callback != null && findCallbackLocked(callback) < 0) {
                boolean addedFirstCallback = mCallbacks.size() == 0;
                addCallbackLocked(callback, handler);
                if (addedFirstCallback) {
                    try {
                        mService.addOnAppsChangedListener(mContext.getPackageName(),
                                mAppsChangedListener);
                    } catch (RemoteException re) {
                        throw re.rethrowFromSystemServer();
                    }
                }
            }
        }
    }

    /**
     * Unregisters a callback that was previously registered.
     *
     * @param callback The callback to unregister.
     * @see #registerCallback(Callback)
     */
    public void unregisterCallback(Callback callback) {
        synchronized (this) {
            removeCallbackLocked(callback);
            if (mCallbacks.size() == 0) {
                try {
                    mService.removeOnAppsChangedListener(mAppsChangedListener);
                } catch (RemoteException re) {
                    throw re.rethrowFromSystemServer();
                }
            }
        }
    }

    /** @return position in mCallbacks for callback or -1 if not present. */
    private int findCallbackLocked(Callback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("Callback cannot be null");
        }
        final int size = mCallbacks.size();
        for (int i = 0; i < size; ++i) {
            if (mCallbacks.get(i).mCallback == callback) {
                return i;
            }
        }
        return -1;
    }

    private void removeCallbackLocked(Callback callback) {
        int pos = findCallbackLocked(callback);
        if (pos >= 0) {
            mCallbacks.remove(pos);
        }
    }

    private void addCallbackLocked(Callback callback, Handler handler) {
        // Remove if already present.
        removeCallbackLocked(callback);
        if (handler == null) {
            handler = new Handler();
        }
        CallbackMessageHandler toAdd = new CallbackMessageHandler(handler.getLooper(), callback);
        mCallbacks.add(toAdd);
    }

    private IOnAppsChangedListener.Stub mAppsChangedListener = new IOnAppsChangedListener.Stub() {

        @Override
        public void onPackageRemoved(UserHandle user, String packageName)
                throws RemoteException {
            if (DEBUG) {
                Log.d(TAG, "onPackageRemoved " + user.getIdentifier() + "," + packageName);
            }
            synchronized (LauncherApps.this) {
                for (CallbackMessageHandler callback : mCallbacks) {
                    callback.postOnPackageRemoved(packageName, user);
                }
            }
        }

        @Override
        public void onPackageChanged(UserHandle user, String packageName) throws RemoteException {
            if (DEBUG) {
                Log.d(TAG, "onPackageChanged " + user.getIdentifier() + "," + packageName);
            }
            synchronized (LauncherApps.this) {
                for (CallbackMessageHandler callback : mCallbacks) {
                    callback.postOnPackageChanged(packageName, user);
                }
            }
        }

        @Override
        public void onPackageAdded(UserHandle user, String packageName) throws RemoteException {
            if (DEBUG) {
                Log.d(TAG, "onPackageAdded " + user.getIdentifier() + "," + packageName);
            }
            synchronized (LauncherApps.this) {
                for (CallbackMessageHandler callback : mCallbacks) {
                    callback.postOnPackageAdded(packageName, user);
                }
            }
        }

        @Override
        public void onPackagesAvailable(UserHandle user, String[] packageNames, boolean replacing)
                throws RemoteException {
            if (DEBUG) {
                Log.d(TAG, "onPackagesAvailable " + user.getIdentifier() + "," + packageNames);
            }
            synchronized (LauncherApps.this) {
                for (CallbackMessageHandler callback : mCallbacks) {
                    callback.postOnPackagesAvailable(packageNames, user, replacing);
                }
            }
        }

        @Override
        public void onPackagesUnavailable(UserHandle user, String[] packageNames, boolean replacing)
                throws RemoteException {
            if (DEBUG) {
                Log.d(TAG, "onPackagesUnavailable " + user.getIdentifier() + "," + packageNames);
            }
            synchronized (LauncherApps.this) {
                for (CallbackMessageHandler callback : mCallbacks) {
                    callback.postOnPackagesUnavailable(packageNames, user, replacing);
                }
            }
        }

        @Override
        public void onPackagesSuspended(UserHandle user, String[] packageNames)
                throws RemoteException {
            if (DEBUG) {
                Log.d(TAG, "onPackagesSuspended " + user.getIdentifier() + "," + packageNames);
            }
            synchronized (LauncherApps.this) {
                for (CallbackMessageHandler callback : mCallbacks) {
                    callback.postOnPackagesSuspended(packageNames, user);
                }
            }
        }

        @Override
        public void onPackagesUnsuspended(UserHandle user, String[] packageNames)
                throws RemoteException {
            if (DEBUG) {
                Log.d(TAG, "onPackagesUnsuspended " + user.getIdentifier() + "," + packageNames);
            }
            synchronized (LauncherApps.this) {
                for (CallbackMessageHandler callback : mCallbacks) {
                    callback.postOnPackagesUnsuspended(packageNames, user);
                }
            }
        }

        @Override
        public void onShortcutChanged(UserHandle user, String packageName,
                ParceledListSlice shortcuts) {
            if (DEBUG) {
                Log.d(TAG, "onShortcutChanged " + user.getIdentifier() + "," + packageName);
            }
            final List<ShortcutInfo> list = shortcuts.getList();
            synchronized (LauncherApps.this) {
                for (CallbackMessageHandler callback : mCallbacks) {
                    callback.postOnShortcutChanged(packageName, user, list);
                }
            }
        }
    };

    private static class CallbackMessageHandler extends Handler {
        private static final int MSG_ADDED = 1;
        private static final int MSG_REMOVED = 2;
        private static final int MSG_CHANGED = 3;
        private static final int MSG_AVAILABLE = 4;
        private static final int MSG_UNAVAILABLE = 5;
        private static final int MSG_SUSPENDED = 6;
        private static final int MSG_UNSUSPENDED = 7;
        private static final int MSG_SHORTCUT_CHANGED = 8;

        private LauncherApps.Callback mCallback;

        private static class CallbackInfo {
            String[] packageNames;
            String packageName;
            boolean replacing;
            UserHandle user;
            List<ShortcutInfo> shortcuts;
        }

        public CallbackMessageHandler(Looper looper, LauncherApps.Callback callback) {
            super(looper, null, true);
            mCallback = callback;
        }

        @Override
        public void handleMessage(Message msg) {
            if (mCallback == null || !(msg.obj instanceof CallbackInfo)) {
                return;
            }
            CallbackInfo info = (CallbackInfo) msg.obj;
            switch (msg.what) {
                case MSG_ADDED:
                    mCallback.onPackageAdded(info.packageName, info.user);
                    break;
                case MSG_REMOVED:
                    mCallback.onPackageRemoved(info.packageName, info.user);
                    break;
                case MSG_CHANGED:
                    mCallback.onPackageChanged(info.packageName, info.user);
                    break;
                case MSG_AVAILABLE:
                    mCallback.onPackagesAvailable(info.packageNames, info.user, info.replacing);
                    break;
                case MSG_UNAVAILABLE:
                    mCallback.onPackagesUnavailable(info.packageNames, info.user, info.replacing);
                    break;
                case MSG_SUSPENDED:
                    mCallback.onPackagesSuspended(info.packageNames, info.user);
                    break;
                case MSG_UNSUSPENDED:
                    mCallback.onPackagesUnsuspended(info.packageNames, info.user);
                    break;
                case MSG_SHORTCUT_CHANGED:
                    mCallback.onShortcutsChanged(info.packageName, info.shortcuts, info.user);
                    break;
            }
        }

        public void postOnPackageAdded(String packageName, UserHandle user) {
            CallbackInfo info = new CallbackInfo();
            info.packageName = packageName;
            info.user = user;
            obtainMessage(MSG_ADDED, info).sendToTarget();
        }

        public void postOnPackageRemoved(String packageName, UserHandle user) {
            CallbackInfo info = new CallbackInfo();
            info.packageName = packageName;
            info.user = user;
            obtainMessage(MSG_REMOVED, info).sendToTarget();
        }

        public void postOnPackageChanged(String packageName, UserHandle user) {
            CallbackInfo info = new CallbackInfo();
            info.packageName = packageName;
            info.user = user;
            obtainMessage(MSG_CHANGED, info).sendToTarget();
        }

        public void postOnPackagesAvailable(String[] packageNames, UserHandle user,
                boolean replacing) {
            CallbackInfo info = new CallbackInfo();
            info.packageNames = packageNames;
            info.replacing = replacing;
            info.user = user;
            obtainMessage(MSG_AVAILABLE, info).sendToTarget();
        }

        public void postOnPackagesUnavailable(String[] packageNames, UserHandle user,
                boolean replacing) {
            CallbackInfo info = new CallbackInfo();
            info.packageNames = packageNames;
            info.replacing = replacing;
            info.user = user;
            obtainMessage(MSG_UNAVAILABLE, info).sendToTarget();
        }

        public void postOnPackagesSuspended(String[] packageNames, UserHandle user) {
            CallbackInfo info = new CallbackInfo();
            info.packageNames = packageNames;
            info.user = user;
            obtainMessage(MSG_SUSPENDED, info).sendToTarget();
        }

        public void postOnPackagesUnsuspended(String[] packageNames, UserHandle user) {
            CallbackInfo info = new CallbackInfo();
            info.packageNames = packageNames;
            info.user = user;
            obtainMessage(MSG_UNSUSPENDED, info).sendToTarget();
        }

        public void postOnShortcutChanged(String packageName, UserHandle user,
                List<ShortcutInfo> shortcuts) {
            CallbackInfo info = new CallbackInfo();
            info.packageName = packageName;
            info.user = user;
            info.shortcuts = shortcuts;
            obtainMessage(MSG_SHORTCUT_CHANGED, info).sendToTarget();
        }
    }
}
