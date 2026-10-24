package foundry.veil.impl.client.render.profiler;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import foundry.veil.api.client.render.profiler.RenderProfilerCounter;
import foundry.veil.api.client.render.profiler.VeilRenderProfiler;
import org.jetbrains.annotations.ApiStatus;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Native timestamp intervals, inclusive nested sections and nonblocking completed-frame publication. */
@ApiStatus.Internal
public final class VeilRenderProfilerImpl {
    private static final int QUERIES_PER_POOL = 512;
    private static final char SEPARATOR = '\u001e';
    private static final Set<String> ENABLED_PATHS = new HashSet<>();
    private static final List<String> PATHS = new ArrayList<>();
    private static final List<Frame> PENDING = new ArrayList<>();
    private static final Map<RenderPass, PassState> PASSES = new IdentityHashMap<>();
    private static final ArrayDeque<PassState> PASS_STACK = new ArrayDeque<>();
    private static Map<String, Long> latest = Map.of();
    private static Frame current = new Frame();
    private static GpuDevice device;
    private static BooleanSupplier visible = () -> false;
    private static boolean timingEnabled;

    private VeilRenderProfilerImpl() { }

    public static void bindRuntime(GpuDevice owner, BooleanSupplier viewerVisible) {
        RenderSystem.assertOnRenderThread(); Objects.requireNonNull(owner); Objects.requireNonNull(viewerVisible);
        if (device != null && device != owner) throw new IllegalStateException("GPU profiler already belongs to another device");
        device = owner; visible = viewerVisible;
    }
    public static void unbindRuntime(GpuDevice owner) { if (device == owner) { close(); device = null; visible = () -> false; } }
    public static boolean isSupported() {
        return device != null && Double.isFinite(device.getDeviceInfo().timestampPeriod()) && device.getDeviceInfo().timestampPeriod() > 0;
    }
    private static boolean enabled() { return isSupported() && visible.getAsBoolean(); }
    public static VeilRenderProfiler get() { return enabled() || !PATHS.isEmpty() ? Active.INSTANCE : Inactive.INSTANCE; }
    public static void setEnabled(Collection<String> paths, RenderProfilerCounter[] counters) {
        RenderSystem.assertOnRenderThread(); ENABLED_PATHS.clear(); ENABLED_PATHS.addAll(paths);
        timingEnabled = Arrays.asList(counters).contains(RenderProfilerCounter.GPU_TIME_NANOS);
    }
    /** Called after the genuine frontend pass has opened. Replays CPU scopes that began before the pass. */
    public static void opened(RenderPass pass) {
        RenderSystem.assertOnRenderThread(); Objects.requireNonNull(pass);
        if (PASSES.containsKey(pass) || !enabled()) return;
        PassState state = new PassState(pass); PASSES.put(pass, state); PASS_STACK.push(state);
        for (String path : PATHS) state.begin(path);
    }
    /** Called before genuine native close, while timestamp recording is still legal. */
    public static void closed(RenderPass pass) {
        RenderSystem.assertOnRenderThread(); PassState state = PASSES.remove(pass); if (state == null) return;
        if (PASS_STACK.peek() != state) throw new IllegalStateException("Native profiler passes must close in stack order");
        var activePaths = new ArrayList<>(state.open.keySet());
        for (int i = activePaths.size() - 1; i >= 0; i--) state.end(activePaths.get(i));
        PASS_STACK.pop();
    }
    /** Standalone pass owners can time a section without installing automatic pass hooks. */
    public static Scope begin(RenderPass pass, String name) {
        boolean ownsPass = !PASSES.containsKey(pass); opened(pass);
        VeilRenderProfiler profiler = get(); profiler.push(name);
        return new Scope(pass, profiler, ownsPass);
    }
    public static final class Scope implements AutoCloseable {
        private final RenderPass pass; private final VeilRenderProfiler profiler; private final boolean ownsPass; private boolean closed;
        private Scope(RenderPass pass, VeilRenderProfiler profiler, boolean ownsPass) { this.pass=pass;this.profiler=profiler;this.ownsPass=ownsPass; }
        @Override public void close() { if(this.closed)return;try{this.profiler.pop();}finally{if(this.ownsPass)VeilRenderProfilerImpl.closed(this.pass);this.closed=true;} }
    }
    /** Polls actual native query availability without blocking the render thread or fabricating unfinished values. */
    public static void poll() {
        RenderSystem.assertOnRenderThread();
        while (!PENDING.isEmpty()) {
            Frame frame = PENDING.getFirst(); Map<String, Long> result = frame.result(); if (result == null) break;
            latest = Collections.unmodifiableMap(result); PENDING.removeFirst(); frame.close();
        }
    }
    public static void endFrame() {
        RenderSystem.assertOnRenderThread();
        if (!PASSES.isEmpty()) throw new IllegalStateException("End the native render passes before ending the profiler frame");
        if (!PATHS.isEmpty()) {
            LoggerFactory.getLogger("Veil").error("Mismatched GPU profiler push/pop; clearing the CPU path stack"); PATHS.clear();
        }
        if (!current.paths.isEmpty() || !current.pools.isEmpty()) PENDING.add(current);
        current = new Frame(); poll();
    }
    /** Dispose before the bound native device shuts down. */
    public static void close() {
        RenderSystem.assertOnRenderThread();
        for (RenderPass pass : List.copyOf(PASSES.keySet())) {
            // Caller must retire actual passes first; writing into a closed pass would corrupt native command ownership.
            if (!PASSES.get(pass).open.isEmpty()) throw new IllegalStateException("Close live native profiler passes before device disposal");
        }
        PASSES.clear(); PASS_STACK.clear(); PATHS.clear(); current.close(); current = new Frame();
        PENDING.forEach(Frame::close); PENDING.clear(); latest = Map.of(); ENABLED_PATHS.clear(); timingEnabled = false;
    }
    public static List<ResultField> getCounters(String sectionPath, RenderProfilerCounter sortedStatistic) {
        poll(); String prefix=sectionPath.isEmpty()?"":sectionPath+SEPARATOR; long local=0, global=0;
        for(var entry:latest.entrySet()) { if(directChild(prefix,entry.getKey()))local+=entry.getValue();if(entry.getKey().indexOf(SEPARATOR)<0)global+=entry.getValue(); }
        List<ResultField> result=new ArrayList<>();
        for(var entry:latest.entrySet())if(directChild(prefix,entry.getKey()))result.add(new ResultField(entry.getKey().substring(prefix.length()),new long[]{entry.getValue()},new long[]{local},new long[]{global},RenderProfilerCounter.ALL,sortedStatistic));
        Collections.sort(result); return List.copyOf(result);
    }
    private static boolean directChild(String prefix,String path){return path.length()>prefix.length()&&path.startsWith(prefix)&&path.indexOf(SEPARATOR,prefix.length())<0;}
    public record ResultField(String name,long[] count,long[] localCount,long[] globalCount,RenderProfilerCounter[] statistics,RenderProfilerCounter sortedStatistic) implements Comparable<ResultField> {
        public ResultField { count=count.clone();localCount=localCount.clone();globalCount=globalCount.clone();statistics=statistics.clone(); }
        @Override public long[] count(){return count.clone();} @Override public long[] localCount(){return localCount.clone();}
        @Override public long[] globalCount(){return globalCount.clone();} @Override public RenderProfilerCounter[] statistics(){return statistics.clone();}
        public double getPercentage(RenderProfilerCounter statistic){int i=statistic.ordinal();return localCount[i]==0?0:100.0*count[i]/localCount[i];}
        public double getGlobalPercentage(RenderProfilerCounter statistic){int i=statistic.ordinal();return globalCount[i]==0?0:100.0*count[i]/globalCount[i];}
        @Override public int compareTo(ResultField other){int order=Long.compare(other.count[sortedStatistic.ordinal()],count[sortedStatistic.ordinal()]);return order==0?name.compareTo(other.name):order;}
        public int getColor(){return(name.hashCode()&11184810)+4473924;}
    }
    private record Stamp(GpuQueryPool pool,int index) { }
    private record Interval(String path,Stamp start,Stamp end) { }
    private static final class Frame implements AutoCloseable {
        final Set<String> paths=new LinkedHashSet<>();final List<GpuQueryPool> pools=new ArrayList<>();final List<Interval> intervals=new ArrayList<>();int next=QUERIES_PER_POOL;
        Stamp timestamp(RenderPass pass){
            if(next==QUERIES_PER_POOL){pools.add(device.createTimestampQueryPool(QUERIES_PER_POOL));next=0;}
            Stamp stamp=new Stamp(pools.getLast(),next++);pass.writeTimestamp(stamp.pool,stamp.index);return stamp;
        }
        Map<String,Long> result(){
            Map<String,Long> values=new LinkedHashMap<>();paths.forEach(path->values.put(path,0L));
            for(Interval interval:intervals){var start=interval.start.pool.getValue(interval.start.index);var end=interval.end.pool.getValue(interval.end.index);
                if(start.isEmpty()||end.isEmpty())return null;
                long ticks=end.getAsLong()-start.getAsLong();if(ticks<0)throw new IllegalStateException("Native timestamp interval is reversed");
                long nanos=Math.round(ticks*device.getDeviceInfo().timestampPeriod());values.merge(interval.path,nanos,Math::addExact);
            }
            return values;
        }
        @Override public void close(){pools.forEach(GpuQueryPool::close);pools.clear();}
    }
    private static final class PassState {
        final RenderPass pass;final Map<String,Stamp> open=new LinkedHashMap<>();PassState(RenderPass pass){this.pass=pass;}
        void begin(String path){if(timingEnabled&&ENABLED_PATHS.contains(path))open.put(path,current.timestamp(pass));}
        void end(String path){Stamp start=open.remove(path);if(start!=null)current.intervals.add(new Interval(path,start,current.timestamp(pass)));}
    }
    private enum Active implements VeilRenderProfiler {
        INSTANCE;
        @Override public void push(String name,RenderProfilerCounter... counters){
            Objects.requireNonNull(name);if(name.isEmpty()||name.indexOf(SEPARATOR)>=0)throw new IllegalArgumentException("Profiler section name must be nonempty and exclude the path separator");
            String path=PATHS.isEmpty()?name:PATHS.getLast()+SEPARATOR+name;PATHS.add(path);current.paths.add(path);
            if(counters.length==0||Arrays.asList(counters).contains(RenderProfilerCounter.GPU_TIME_NANOS)){var state=PASS_STACK.peek();if(state!=null)state.begin(path);}
        }
        @Override public void push(Supplier<String> name,RenderProfilerCounter... counters){push(name.get(),counters);}
        @Override public void pop(){if(PATHS.isEmpty())throw new IllegalStateException("Mismatched native profiler pop");String path=PATHS.removeLast();var state=PASS_STACK.peek();if(state!=null)state.end(path);}
        @Override public void popPush(String name,RenderProfilerCounter... counters){pop();push(name,counters);}
        @Override public void popPush(Supplier<String> name,RenderProfilerCounter... counters){popPush(name.get(),counters);}
    }
    private enum Inactive implements VeilRenderProfiler {
        INSTANCE;
        @Override public void push(String name,RenderProfilerCounter... counters){} @Override public void push(Supplier<String> name,RenderProfilerCounter... counters){}
        @Override public void pop(){} @Override public void popPush(String name,RenderProfilerCounter... counters){} @Override public void popPush(Supplier<String> name,RenderProfilerCounter... counters){}
    }
}
