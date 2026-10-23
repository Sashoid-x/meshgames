package com.meshgames.app

import android.app.Application

class MeshGamesApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph.get(this)
    }
}
