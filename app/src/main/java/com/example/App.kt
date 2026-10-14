package com.example

import android.app.Application

/**
 * Application entry point.
 *
 * Engine synchronisation is scheduled from [MainActivity] (3-day WorkManager job) and checked
 * silently at every launch from the ViewModel. Python is intentionally *not* started here: it
 * is started lazily on first extraction/setup to keep cold start fast.
 */
class App : Application()
