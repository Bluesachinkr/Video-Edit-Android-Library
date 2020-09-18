package com.android.videoeditpro.utils;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;

import java.util.HashMap;
import java.util.Map;

public final class UiThreadExecutor {

    private static final Handler HANDLER = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            Runnable callback = msg.getCallback();
            if (callback != null) {
                callback.run();
                decrementToken((Token) msg.obj);
            } else {
                super.handleMessage(msg);
            }
        }
    };

    private static final Map<String, Token> TOKENS = new HashMap<>();

    private UiThreadExecutor() {
        // should not be instantiated
    }


    public static void runTask(String id, Runnable task, long delay) {
        if ("".equals(id)) {
            HANDLER.postDelayed(task, delay);
            return;
        }
        long time = SystemClock.uptimeMillis() + delay;
        HANDLER.postAtTime(task, nextToken(id), time);
    }

    private static Token nextToken(String id) {
        synchronized (TOKENS) {
            Token token = TOKENS.get(id);
            if (token == null) {
                token = new Token(id);
                TOKENS.put(id, token);
            }
            token.runnablesCount++;
            return token;
        }
    }

    private static void decrementToken(Token token) {
        synchronized (TOKENS) {
            if (--token.runnablesCount == 0) {
                String id = token.id;
                Token old = TOKENS.remove(id);
                if (old != token) {
                    TOKENS.put(id, old);
                }
            }
        }
    }

    public static void cancelAll(String id) {
        Token token;
        synchronized (TOKENS) {
            token = TOKENS.remove(id);
        }
        if (token == null) {
            return;
        }
        HANDLER.removeCallbacksAndMessages(token);
    }

    private static final class Token {
        int runnablesCount = 0;
        final String id;

        private Token(String id) {
            this.id = id;
        }
    }

}
