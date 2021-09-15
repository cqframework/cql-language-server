/**
 * @author SYESILDAG
 */
package org.opencds.cqf.cql.ls;

import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import java.lang.Thread;

public class DebounceExecutor
{
    private ScheduledExecutorService executor;
    private ScheduledFuture<Object> future;

    public DebounceExecutor()
    {
        this.executor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory()
        {
            @Override
            public Thread newThread(Runnable r)
            {
                Thread t = new Thread(r, "Debouncer");
                t.setDaemon(true);
                return t;
            }
        });
    }

    public ScheduledFuture<Object> debounce(long delay, Callable<Object> task)
    {
        if (this.future != null && !this.future.isDone())
            this.future.cancel(false);

        return this.future = this.executor.schedule(task, delay, TimeUnit.MILLISECONDS);
    }
}
