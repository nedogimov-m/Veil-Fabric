import foundry.veil.api.util.CompositeReloadListener;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import java.lang.reflect.Proxy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual 26.3 shared-state and barrier contracts, including failed preparation. */
public final class NativeReloadTransactionAcceptance {
    public static void main(String[] args) throws Exception {
        final var resources=(ResourceManager)Proxy.newProxyInstance(ResourceManager.class.getClassLoader(),new Class<?>[]{ResourceManager.class},(self,method,values)->{throw new AssertionError("No resource IO belongs to this transaction test");});
        final var state=new PreparableReloadListener.SharedState(resources);final var prepared=new AtomicInteger();final var applied=new AtomicInteger();final var initialized=new AtomicInteger();final var upstream=new AtomicInteger();
        final var release=new CompletableFuture<Void>();
        try(var workers=Executors.newFixedThreadPool(2)) {
            PreparableReloadListener child=new PreparableReloadListener(){
                @Override public void prepareSharedState(SharedState shared){check(shared==state,"exact shared native state");initialized.incrementAndGet();}
                @Override public CompletableFuture<Void> reload(SharedState shared,Executor prepare,PreparationBarrier barrier,Executor apply){
                    check(shared.resourceManager()==resources,"actual native shared resource owner");
                    return CompletableFuture.supplyAsync(prepared::incrementAndGet,prepare).thenCompose(barrier::wait).thenAcceptAsync(value->{check(prepared.get()==2,"all preparations completed before apply");applied.incrementAndGet();},apply);
                }
            };
            final var transaction=CompositeReloadListener.of(child,child);transaction.prepareSharedState(state);
            final var completion=transaction.reload(state,workers,new PreparableReloadListener.PreparationBarrier(){public <T> CompletableFuture<T> wait(T value){upstream.incrementAndGet();return release.thenApply(ignored->value);}},Runnable::run);
            final long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(upstream.get()==0&&System.nanoTime()<deadline)Thread.onSpinWait();
            check(upstream.get()==1&&prepared.get()==2&&applied.get()==0&&!completion.isDone(),"native upstream barrier owns apply transition exactly once");
            release.complete(null);completion.get(5,TimeUnit.SECONDS);check(applied.get()==2&&initialized.get()==2,"every supplied listener initializes/applies exactly once");
            PreparableReloadListener waiting=(shared,prepare,barrier,apply)->barrier.wait(null);
            PreparableReloadListener failed=(shared,prepare,barrier,apply)->CompletableFuture.failedFuture(new IllegalArgumentException("expected failed preparation"));
            expectFailure(CompositeReloadListener.of(waiting,failed).reload(state,workers,immediate(),Runnable::run),IllegalArgumentException.class);
            expectFailure(CompositeReloadListener.of(waiting,waiting).reload(state,workers,new PreparableReloadListener.PreparationBarrier(){public <T> CompletableFuture<T> wait(T value){return CompletableFuture.failedFuture(new IllegalStateException("expected failed outer barrier"));}},Runnable::run),IllegalStateException.class);
            final var emptyCount=new AtomicInteger();CompositeReloadListener.of().reload(state,workers,new PreparableReloadListener.PreparationBarrier(){public <T> CompletableFuture<T> wait(T value){emptyCount.incrementAndGet();return CompletableFuture.completedFuture(value);}},Runnable::run).get(5,TimeUnit.SECONDS);
            check(emptyCount.get()==1&&CompositeReloadListener.of(child)==child,"empty and singleton transaction contracts");
        }
        System.out.println("PASS native shared-state forwarding, concurrent prepare barrier, exact apply, preparation failure, upstream failure, empty/singleton contracts");
    }
    private static PreparableReloadListener.PreparationBarrier immediate(){return new PreparableReloadListener.PreparationBarrier(){public <T> CompletableFuture<T> wait(T value){return CompletableFuture.completedFuture(value);}};}
    private static void expectFailure(CompletableFuture<?> future,Class<? extends Throwable> type)throws Exception{try{future.get(5,TimeUnit.SECONDS);throw new AssertionError("Expected native reload failure");}catch(ExecutionException failure){check(type.isInstance(failure.getCause()),"native failure reaches waiting participants without deadlock");}}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
