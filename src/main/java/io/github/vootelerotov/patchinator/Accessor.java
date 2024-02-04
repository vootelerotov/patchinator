package io.github.vootelerotov.patchinator;

import com.spotify.githubclient.shade.okhttp3.Cache;
import com.spotify.githubclient.shade.okhttp3.ConnectionPool;
import com.spotify.githubclient.shade.okhttp3.Dispatcher;
import com.spotify.githubclient.shade.okhttp3.OkHttpClient;

import java.util.concurrent.ExecutorService;

/**
 * This is needed to work around an issue with shaded OkHttp3.
 * See <a href="https://github.com/spotify/github-java-client/issues/179">...</a>.
 */
class Accessor {

    public static Dispatcher getDispatcher(OkHttpClient client) {
        return client.dispatcher();
    }

    public static ExecutorService getExecutorService(Dispatcher dispatcher) {
        return dispatcher.executorService();
    }

    public static ConnectionPool getConnectionPool(OkHttpClient client) {
        return client.connectionPool();
    }

    public static Cache getCache(OkHttpClient client) {
        return client.cache();
    }

}
