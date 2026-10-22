package com.storelense.gateBt

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class GateBtApplication : Application() {
    @Volatile private var activityRef = WeakReference<Activity>(null)

    internal val currentActivity: Activity?
        get() = activityRef.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                activityRef = WeakReference(activity)
            }

            override fun onActivityResumed(activity: Activity) {
                activityRef = WeakReference(activity)
            }

            override fun onActivityDestroyed(activity: Activity) {
                if (activityRef.get() === activity) activityRef.clear()
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
    }
}
