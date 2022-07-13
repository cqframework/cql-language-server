/**
 * @author SYESILDAG
 */
package org.opencds.cqf.cql.ls.server;

import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public class DebounceExecutor
{
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> future;

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

    public ScheduledFuture<?> debounce(long delay, Callable<?> task)
    {
        if (this.future != null && !this.future.isDone())
            this.future.cancel(false);

        return this.future = this.executor.schedule(task, delay, TimeUnit.MILLISECONDS);
    }

    public ScheduledFuture<?> debounce(long delay, Runnable task)
    {
        if (this.future != null && !this.future.isDone())
            this.future.cancel(false);

        return this.future = this.executor.schedule(task, delay, TimeUnit.MILLISECONDS);
    }
}
