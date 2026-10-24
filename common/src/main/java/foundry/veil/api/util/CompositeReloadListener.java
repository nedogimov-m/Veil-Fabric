package foundry.veil.api.util;

import net.minecraft.server.packs.resources.PreparableReloadListener;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/** One native shared-state reload transaction with a common preparation barrier. */
public final class CompositeReloadListener implements PreparableReloadListener {
    private final PreparableReloadListener[] listeners;
    private CompositeReloadListener(PreparableReloadListener[] listeners){this.listeners=listeners.clone();}
    public static PreparableReloadListener of(PreparableReloadListener... listeners){
        Arrays.stream(listeners).forEach(Objects::requireNonNull);
        return listeners.length==1?listeners[0]:new CompositeReloadListener(listeners);
    }
    @Override public void prepareSharedState(SharedState state){for(var listener:listeners)listener.prepareSharedState(state);}
    @Override public CompletableFuture<Void> reload(SharedState state,Executor prepare,PreparationBarrier upstream,Executor apply){
        if(listeners.length==0)return upstream.wait(null).thenApply(ignored->null);
        final var ready=new CompletableFuture<Void>();final var remaining=new AtomicInteger(listeners.length);
        final var results=new CompletableFuture<?>[listeners.length];
        for(int i=0;i<listeners.length;i++){
            final var crossed=new AtomicBoolean();
            PreparationBarrier barrier=new PreparationBarrier(){
                @Override public <T> CompletableFuture<T> wait(T value){
                    if(crossed.compareAndSet(false,true)&&remaining.decrementAndGet()==0){
                        try{upstream.wait(null).whenComplete((ignored,failure)->{if(failure!=null)ready.completeExceptionally(failure);else ready.complete(null);});}
                        catch(Throwable failure){ready.completeExceptionally(failure);}
                    }
                    return ready.thenApply(ignored->value);
                }
            };
            try{results[i]=listeners[i].reload(state,prepare,barrier,apply);}
            catch(Throwable failure){results[i]=CompletableFuture.failedFuture(failure);}
            results[i].whenComplete((ignored,failure)->{if(failure!=null)ready.completeExceptionally(failure);});
        }
        return CompletableFuture.allOf(results);
    }
    @Override public String getName(){return "CompositeListener["+Arrays.stream(listeners).map(PreparableReloadListener::getName).collect(Collectors.joining(","))+"]";}
}
