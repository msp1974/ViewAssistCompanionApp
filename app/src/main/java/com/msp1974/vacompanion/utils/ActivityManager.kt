package com.msp1974.vacompanion.utils

import android.app.Activity
import android.app.Application.ActivityLifecycleCallbacks
import android.os.Bundle
import com.msp1974.vacompanion.VACAApplication


class ActivityManager(myApplication: VACAApplication) : ActivityLifecycleCallbacks {
    var activity: Activity? = null
        private set


    init {
        myApplication.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityCreated(activity: Activity, bundle: Bundle?) {
        this.activity = activity
    }

    override fun onActivityStarted(activity: Activity) {
        this.activity = activity
    }

    override fun onActivityResumed(activity: Activity) {
        this.activity = activity
    }

    override fun onActivityPaused(activity: Activity) {
    }

    override fun onActivityStopped(activity: Activity) {
        if (this.activity === activity) {
            this.activity = null
        }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (this.activity === activity) {
            this.activity = null
        }
    }
}
