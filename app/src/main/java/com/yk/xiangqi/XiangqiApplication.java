package com.yk.xiangqi;

import android.app.Application;

/**
 * 应用级业务对象不依赖 Activity 或 Compose 生命周期。
 */
public final class XiangqiApplication extends Application {
    private Settings settings;
    private GameRuntime gameRuntime;

    @Override
    public void onCreate() {
        super.onCreate();
        settings = new Settings(this);
        gameRuntime = new GameRuntime();
    }

    public Settings settings() {
        return settings;
    }

    public GameRuntime gameRuntime() {
        return gameRuntime;
    }
}
