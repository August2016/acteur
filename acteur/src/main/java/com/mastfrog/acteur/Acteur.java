/*
 * The MIT License
 *
 * Copyright 2013 Tim Boudreau.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.mastfrog.acteur;

import com.google.common.net.MediaType;
import com.mastfrog.acteur.Acteur.BaseState;
import com.mastfrog.acteur.errors.Err;
import com.mastfrog.acteur.errors.ErrorRenderer;
import com.mastfrog.acteur.errors.ErrorResponse;
import com.mastfrog.acteur.errors.ResponseException;
import com.mastfrog.acteur.headers.HeaderValueType;
import com.mastfrog.acteur.headers.Headers;
import com.mastfrog.acteurbase.AbstractActeur;
import com.mastfrog.acteurbase.ActeurResponseFactory;
import com.mastfrog.acteurbase.Chain;
import com.mastfrog.acteurbase.Deferral;
import com.mastfrog.acteurbase.Deferral.Resumer;
import com.mastfrog.giulius.Dependencies;
import com.mastfrog.giulius.scope.ReentrantScope;
import com.mastfrog.settings.Settings;
import com.mastfrog.util.Checks;
import static com.mastfrog.util.Checks.noNullElements;
import static com.mastfrog.util.Checks.notNull;
import com.mastfrog.util.Exceptions;
import com.mastfrog.util.Invokable;
import com.mastfrog.util.function.ThrowingConsumer;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.HttpResponseStatus;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.NO_CONTENT;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;

/**
 * A single piece of logic which can
 * <ul>
 * <li>Reject an HTTP request</li>
 * <li>Validate an HTTP request and allow the next Acteur in the chain to
 * process it</li>
 * <li>Initiate an HTTP response</li>
 * </ul>
 * Acteurs are aggregated into a list in a {@link Page}. All of an Acteur's work
 * happens either in its constructor, prior to a call to setState(), or in its
 * overridden getState() method. The state determines whether processing of the
 * current list of Acteurs will continue, or if not, what happens to it.
 * <p/>
 * Acteurs are constructed by Guice - in fact, what a Page has is usually just a
 * list of classes. Objects they need, such as the current request
 * {@link HttpEvent} can simply be constructor parameters if the constructor is
 * annotated with Guice's &#064;Inject.
 * <p/>
 * An Acteur may construct some objects which will then be included in the set
 * of objects the next Acteur in the chain can request for injection in its
 * constructor parameters.
 * <p/>
 * A number of inner classes are provided which can be used as standard states.
 * <p/>
 * Acteurs may be - in fact, are likely to be - called asynchronously. For a
 * given page, they will always be called in the sequence that page lists them
 * in, but there is no guarantee that any two adjacent Acteurs will be called on
 * the same thread. Any shared state should take the form of objects put into
 * the context when the output State is created.
 * <p/>
 * This makes it possible to incrementally respond to a request, for example,
 * doing just enough computation to determine if a NOT MODIFIED response is
 * possible without computing the complete response (which in that case would be
 * thrown away).
 * <p/>
 * Their asynchronous nature means that many requests can be handled
 * simultaneously and run small bits of logic, interleaved, on fewer threads,
 * for maximum throughput.
 *
 * <h4>Support for asynchronous computation</h4>
 *
 * Acteurs are asynchronous in nature, but by default the framework assumes they
 * can be run one after the other. In the case an Acteur needs to call out to
 * some library (perhaps an asynchronous database driver) which will
 * asynchronously do some work and invoke a callback on another thread, there
 * are a number of options to <i>defer</i> execution of further acteurs until
 * the result is ready.
 * <p/>
 * With support for Java 8, a variety of flexible ways to do that are available
 * (the original mechanism, requesting injection of a <code>Deferral</code>
 * object is also still supported):
 * <ul>
 * <li>Use <code>then()</code> to pass in a <code>CompletableFuture</code> whose
 * result object will be serialized as the response.</li>
 * <li>Use <code>continueAfter()</code> to pass one <i>or more</i> <code>
 * CompletableFuture</code>s and have all of their results available for
 * injection into subsequent acteurs in the chain</li>
 * <li>Use <code>defer()</code> to pause the chain and get back an instance of
 * <code>CompletableFuture</code> which you can call <code>complete()</code> or
 * <code>completeExceptionally()</code> on once the work is complete or an error
 * has occurred</li>
 * <li>Use <code>deferThenRespond()</code> to pause the chain and get back an
 * instance of <code>CompletableFuture</code> whose contents will be marshalled
 * into the response once you call <code>complete()</code> or
 * <code>completeExceptionally()</code>.</li>
 * </ul>
 *
 * With any of these newer methods, you should not call <code>next()</code> -
 * the framework takes care of that (with an injected <code>Deferral</code>
 * instance, you did). In all cases, if a <code>CompletableFuture</code>
 * completes exceptionally, an error response is generated and no subsequent
 * Acteurs are run.
 * <p/>
 * With either of the methods which return a <code>CompletableFuture</code>, you
 * <b>must call <code>complete()</code> or <code>completeExceptionally()
 * <i>no matter what happens</i></b> or your application will suffer from
 * "request dropped on the floor" bugs where the connection is held open but
 * no response is ever sent.
 *
 * @author Tim Boudreau
 */
public abstract class Acteur extends AbstractActeur<Response, ResponseImpl, State> {

    class BaseState extends com.mastfrog.acteur.State {

        protected final Page page;

        BaseState(Object... context) {
            super(context);
            page = Page.get();
            if (page == null) {
                throw new IllegalStateException("Page not set");
            }
        }

        BaseState(boolean rejected) {
            super(rejected);
            page = Page.get();
            if (page == null) {
                throw new IllegalStateException("Page not set");
            }
        }

        @Override
        public final Acteur getActeur() {
            return Acteur.this;
        }

    }

    /**
     * Create an acteur.
     *
     * @param async If true, the framework should prefer to run the <i>next</i>
     * action asynchronously
     */
    protected Acteur() {
        super(INSTANCE);
    }
    private static final RT INSTANCE = new RT();

    @Override
    protected com.mastfrog.acteur.State getState() {
        return super.getState();
    }

    private static final Object[] EMPTY = new Object[0];

    public Object[] getContextContribution() {
        return EMPTY;
    }

    final Throwable creationStackTrace() {
        return creationStackTrace;
    }

    static class RT extends ActeurResponseFactory<Response, ResponseImpl> {

        @Override
        protected ResponseImpl create() {
            return new ResponseImpl();
        }

        @Override
        protected boolean isFinished(ResponseImpl obj) {
            return obj != null && obj.status != null;
        }

        @Override
        protected boolean isModified(ResponseImpl obj) {
            return obj != null && obj.isModified();
        }

    }

    /**
     * If you write an acteur which delegates to another one, implement this so
     * that that other one's changes to the response will be picked up. This
     * pattern is sometimes used where a choice is made about which acteur to
     * call next.
     */
    public interface Delegate {

        /**
         * Get the acteur being delegated to
         *
         * @return An acteur
         */
        Acteur getDelegate();
    }

    protected <T> Acteur add(HeaderValueType<T> decorator, T value) {
        response().add(decorator, value);
        return this;
    }

    @Override
    protected ResponseImpl getResponse() {
        if (this instanceof Delegate) {
            return ((Delegate) this).getDelegate().getResponse();
        }
        return super.getResponse();
    }

    protected <T> T get(HeaderValueType<T> header) {
        return response().get(header);
    }

    public final Acteur setResponseCode(HttpResponseStatus status) {
        response().status(status);
        return this;
    }

    public final Acteur setMessage(Object message) {
        response().content(message);
        return this;
    }

    public final Acteur setChunked(boolean chunked) {
        response().chunked(chunked);
        return this;
    }

    @Override
    protected final Response response() {
        if (this instanceof Delegate) {
            return ((Delegate) this).getDelegate().response();
        }
        return super.response();
    }

    static Acteur error(Acteur errSource, Page page, Throwable t, Event<?> evt, boolean log) {
        try {
            return new ErrorActeur(errSource, evt, page, t, true, log);
        } catch (IOException ex) {
            page.application.internalOnError(t);
            try {
                return new ErrorActeur(errSource, evt, page, t, true, log);
            } catch (IOException ex1) {
                return Exceptions.chuck(ex1);
            }
        }
    }

    public void describeYourself(Map<String, Object> into) {
    }

    protected final Acteur noContent() {
        setState(new RespondWith(NO_CONTENT));
        return this;
    }

    protected final Acteur badRequest() {
        setState(new RespondWith(BAD_REQUEST));
        return this;
    }

    protected final Acteur badRequest(Object msg) {
        setState(new RespondWith(BAD_REQUEST, msg));
        return this;
    }

    protected final Acteur notFound() {
        setState(new RespondWith(NOT_FOUND));
        return this;
    }

    protected final Acteur notFound(Object msg) {
        setState(new RespondWith(NOT_FOUND, msg));
        return this;
    }

    protected final Acteur ok(Object msg) {
        setState(new RespondWith(OK, msg));
        return this;
    }

    protected final Acteur ok() {
        setState(new RespondWith(OK));
        return this;
    }

    protected final Acteur reply(HttpResponseStatus status) {
        setState(new RespondWith(status));
        return this;
    }

    protected final Acteur reply(HttpResponseStatus status, Object msg) {
        setState(new RespondWith(status, msg));
        return this;
    }

    protected final Acteur reply(Err err) {
        setState(new RespondWith(err));
        return this;
    }

    protected final Acteur reject() {
        setState(new RejectedState());
        return this;
    }

    protected final Acteur next(Object... context) {
        if (context == null || context.length == 0) {
            setState(new ConsumedState());
        } else {
            setState(new ConsumedLockedState(context));
        }
        return this;
    }

    /**
     * Continue the Acteur chain, running any subsequent acteurs, once the
     * passed CompletionStages (e.g. CompletableFuture) have completed - this
     * makes it possible to run ad-hoc asynchronous logic with the acteur chain
     * paused, and automatically resume it when finished.
     *
     * @param stages One or more CompletionStage instances.
     *
     * @return This acteur
     * @since 2.1.0
     */
    @SuppressWarnings("unchecked")
    protected final Acteur continueAfter(CompletionStage<?>... stages) {
        Checks.nonZero("stages", noNullElements("stages", notNull("stages", stages)).length);
        Dependencies deps = Page.get().getApplication().getDependencies();
        Chain chain = deps.getInstance(Chain.class);
        chain.insert(CheckThrownActeur.class);
        List<Object> l = new CopyOnWriteArrayList<>();
        AtomicBoolean alreadyResumed = new AtomicBoolean();
        AtomicInteger count = new AtomicInteger();
        return then((Resumer resumer) -> {
            for (CompletionStage<?> c : stages) {
                c.whenComplete((Object o, Throwable t) -> {
                    if (t != null) {
                        if (alreadyResumed.compareAndSet(false, true)) {
                            l.add(DeferredComputationResult.thrown(t));
                            resumer.resume(l.toArray(new Object[l.size()]));
                        }
                    } else {
                        if (o != null) {
                            l.add(o);
                        }
                        if (count.incrementAndGet() == stages.length && alreadyResumed.compareAndSet(false, true)) {
                            l.add(DeferredComputationResult.empty());
                            resumer.resume(l.toArray(new Object[l.size()]));
                        }
                    }
                });
            }
        });
    }

    /**
     * Pause the Acteur chain until external code completes the returned
     * CompletableFuture, then use the result of that computation as the
     * response (JSON or whatever the application is configured to marshal
     * responses to).
     *
     * @param <T> The type parameter for the returned CompletableFuture.
     * @return A CompletableFuture
     * @since 2.1.0
     */
    protected final <T> CompletableFuture<T> deferThenRespond() {
        CompletableFuture<T> result = new CompletableFuture<T>();
        then(result);
        return result;
    }

    /**
     * Pause the Acteur chain until external code completes the returned
     * CompletableFuture, then restart the acteur chain with the result of the
     * computation available for injection into subsequent acteurs.
     *
     * @param <T> The type parameter for the returned CompletableFuture.
     * @return A CompletableFuture
     * @since 2.1.0
     */
    protected final <T> CompletableFuture<T> defer() {
        CompletableFuture<T> result = new CompletableFuture<>();
        continueAfter(result);
        return result;
    }

    /**
     * Defer the acteur chain, allowing the passed ThrowingConsumer to run any
     * amount of asynchonous logic, and call <code>Resumer.resume()</code> with
     * objects to be available for injection into subsequent Acteurs.
     *
     * @param cons The consumer
     * @return This acteur
     * @since 2.1.0
     */
    protected final Acteur then(ThrowingConsumer<Resumer> cons) {
        next();
        Deferral def = Page.get().getApplication().getDependencies().getInstance(Deferral.class);
        def.defer((Resumer res) -> {
            cons.apply(res);
        });
        return this;
    }

    /**
     * Pass a CompletionStage (for example, CompletableFuture) which will
     * execute asynchronously. The acteur chain will be paused until the stage
     * completes; if it completes exceptionally, the usual handling for thrown
     * exceptions will generate the response; if it completes normally, it will
     * be encoded as the response object (by default, to JSON).
     *
     * @param <T> The return type of the completion stage
     * @param c A completion stage
     * @return This acteur
     * @since 2.1.0
     */
    protected final <T> Acteur then(CompletionStage<T> c) {
        return then(c, null);
    }

    /**
     * Pass a CompletionStage (for example, CompletableFuture) which will
     * execute asynchronously. The acteur chain will be paused until the stage
     * completes; if it completes exceptionally, the usual handling for thrown
     * exceptions will generate the response; if it completes normally, it will
     * be encoded as the response object (by default, to JSON).
     *
     * @param <T> The return type of the completion stage
     * @param c The completion stage
     * @param successStatus An HttpResponseStatus to use if the stage completes
     * normally
     * @since 2.1.0
     * @return This acteur
     */
    @SuppressWarnings("unchecked")
    protected final <T> Acteur then(CompletionStage<T> c, HttpResponseStatus successStatus) {
        Dependencies deps = Page.get().getApplication().getDependencies();
        Chain chain = deps.getInstance(Chain.class);
        chain.add(DeferredComputationResultActeur.class);
        return then((Resumer r) -> {
            c.whenComplete((t, thrown) -> {
                r.resume(new DeferredComputationResult(t, thrown, successStatus));
            });
        });
    }

    static final class CheckThrownActeur extends Acteur {

        @Inject
        CheckThrownActeur(DeferredComputationResult res) throws Throwable {
            if (res.thrown != null) {
                if (res.thrown instanceof ResponseException) {
                    // Let the normal exception response generation code handle it
                    throw res.thrown;
                } else {
                    reply(Err.of(res.thrown));
                }
            } else {
                next();
            }
        }
    }

    /**
     * A shorthand state for responding with a particular http response code and
     * optional message, which if non-string, will be rendered as JSON.
     */
    public class RespondWith extends BaseState {

        public RespondWith(int status) {
            this(HttpResponseStatus.valueOf(status));
        }

        public RespondWith(HttpResponseStatus status) {
            this(status, null);
        }

        public RespondWith(int status, Object msg) {
            this(HttpResponseStatus.valueOf(status), msg);
        }

        public RespondWith(ErrorResponse err) {
            super(false);
            setResponseCode(err.status());
            ErrorRenderer ren = getLockedPage().getApplication().getDependencies().getInstance(ErrorRenderer.class);
            Object message;
            try {
                message = ren.render(err, getLockedPage().getApplication().getDependencies().getInstance(HttpEvent.class));
                setMessage(message);
            } catch (IOException ex) {
                Exceptions.chuck(ex);
            }
        }

        /**
         * Acteur.this; Response which uses JSON
         *
         * @param status
         * @param msg
         */
        public RespondWith(HttpResponseStatus status, Object msg) {
            super(false);
            if (page == null) {
                IllegalStateException e = new IllegalStateException("Called outside ActionsImpl.onEvent");
                e.printStackTrace();
                throw e;
            }
            setResponseCode(status);
            if (msg != null) {
                setMessage(msg);
            }
        }

        @Override
        public String toString() {
            return "Respond with " + getResponse().internalStatus() + " - "
                    + super.toString() + " - " + getResponse().getMessage();
        }
    }

    /**
     * A state indicating the acteur neither accepts nor definitively refuses a
     * request.
     */
    protected class RejectedState extends BaseState {

        public RejectedState() {
            super(true);
            if (page == null) {
                throw new IllegalStateException("Called outside ActionsImpl.onEvent");
            }
        }

        public RejectedState(HttpResponseStatus status) {
            super(true);
            setResponseCode(status);
        }
    }

    /**
     * State indicating that this acteur chain is taking responsibility for
     * responding to the request. It may optionally include objects which should
     * be available for injection into subsequent acteurs.
     */
    protected class ConsumedState extends BaseState {

        private final Page page;
        private final Object[] context;

        public ConsumedState(Object... context) {
            super(false);
            page = Page.get();
            if (page == null) {
                throw new IllegalStateException("Called outside ActionsImpl.onEvent");
            }
            this.context = context;
        }
    }

    protected class ConsumedLockedState extends BaseState {

        private final Page page;

        public ConsumedLockedState(Object... context) {
            super(context);
            page = Page.get();
            if (page == null) {
                throw new IllegalStateException("Called outside ActionsImpl.onEvent");
            }
        }
    }

    /**
     * Set a response writer which can iteratively be called back until the
     * response is completed. The writer will be created dynamically but any
     * object currently in scope can be injected into it.
     *
     * @param <T> The type of writer
     * @param writerType The writer class
     */
    protected final <T extends ResponseWriter> Acteur setResponseWriter(Class<T> writerType) {
        Page page = Page.get();
        Dependencies deps = page.getApplication().getDependencies();
        HttpEvent evt = deps.getInstance(HttpEvent.class);
        response();
        getResponse().setWriter(writerType, deps, evt);
        return this;
    }

    /**
     * Set a response writer which can iteratively be called back until the
     * response is completed.
     *
     * @param <T> The type of writer
     * @param writer The writer
     */
    protected final <T extends ResponseWriter> Acteur setResponseWriter(T writer) {
        Page page = Page.get();
        Dependencies deps = page.getApplication().getDependencies();
        HttpEvent evt = deps.getInstance(HttpEvent.class);
        response();
        getResponse().setWriter(writer, deps, evt);
        return this;
    }

    /**
     * Set a ChannelFutureListener which will be called after headers are
     * written and flushed to the socket; prefer
     * <code>setResponseWriter()</code> to this method unless you are not using
     * chunked encoding and want to stream your response (in which case, be sure
     * to setChunked(false) or you will have encoding errors).
     * <p/>
     * This method will dynamically construct the passed listener type using
     * Guice, and including all of the contents of the scope in which this call
     * was made.
     *
     * @param <T> a type
     * @param type The type of listener
     */
    protected final <T extends ChannelFutureListener> Acteur setResponseBodyWriter(final Class<T> type) {
        final Page page = Page.get();
        final Dependencies deps = page.getApplication().getDependencies();
        ReentrantScope scope = page.getApplication().getRequestScope();
        final AtomicReference<ChannelFuture> fut = new AtomicReference<>();

        // An object which can instantiate and run the listener
        class I extends Invokable<ChannelFuture, Void, Exception> {

            private ChannelFutureListener delegate;

            @Override
            public Void run(ChannelFuture argument) throws Exception {
                if (delegate == null) {
                    delegate = deps.getInstance(type);
                }
                delegate.operationComplete(argument);
                return null;
            }

            @Override
            public String toString() {
                return "Delegate for " + type;
            }
        }

        // A runnable-like object which takes an argument, and which can
        // be wrapped by the scope in order to reconstitute the scope contents
        // as they are now before constructing the actual listener
        final Invokable<ChannelFuture, Void, Exception> listenerInvoker
                = scope.wrap(new I(), fut);

        // Wrap this in a dummy listener which will create the real one on
        // demand
        class C implements ChannelFutureListener {

            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                try (AutoCloseable cl = Page.set(page)) {
                    fut.set(future);
                    listenerInvoker.run(future);
                }
            }

            @Override
            public String toString() {
                return "Delegate for " + listenerInvoker;
            }
        }

        ChannelFutureListener l = new C();
        setResponseBodyWriter(l);
        return this;
    }

    protected final Dependencies dependencies() {
        final Page p = Page.get();
        final Application app = p.getApplication();
        return app.getDependencies();
    }

    /**
     * Set a ChannelFutureListener which will be called after headers are
     * written and flushed to the socket; prefer
     * <code>setResponseWriter()</code> to this method unless you are not using
     * chunked encoding and want to stream your response (in which case, be sure
     * to chunked(false) or you will have encoding errors).
     *
     * @param listener
     */
    public final Acteur setResponseBodyWriter(final ChannelFutureListener listener) {
        if (listener == ChannelFutureListener.CLOSE || listener == ChannelFutureListener.CLOSE_ON_FAILURE) {
            response();
            getResponse().contentWriter(listener);
            return this;
        }
        Page p = Page.get();
        final Application app = p.getApplication();
        class WL implements ChannelFutureListener, Callable<Void> {

            private ChannelFuture future;
            private final Callable<Void> wrapper = app.getRequestScope().wrap(this);

            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                this.future = future;
                wrapper.call();
            }

            @Override
            public Void call() throws Exception {
                listener.operationComplete(future);
                return null;
            }

            @Override
            public String toString() {
                return "Scope wrapper for " + listener;
            }
        }
        response().contentWriter(new WL());
        return this;
    }

    public static Acteur wrap(final Class<? extends Acteur> type, final Dependencies deps) {
        Checks.notNull("type", type);
        final Charset charset = deps.getInstance(Charset.class);
        return new WrapperActeur(deps, charset, type);
    }

    static class WrapperActeur extends Acteur implements Delegate {

        private final Dependencies deps;
        private final Charset charset;
        private final Class<? extends Acteur> type;

        WrapperActeur(Dependencies deps, Charset charset, Class<? extends Acteur> type) {
            this.deps = deps;
            this.charset = charset;
            this.type = type;
        }
        Acteur acteur;

        public Class<? extends Acteur> type() {
            return type;
        }

        @Override
        public void describeYourself(Map<String, Object> into) {
            try {
                delegate().describeYourself(into);
            } catch (Exception e) {
                //ok - we may be called without an event to play with
            }
        }

        boolean inOnError;

        protected void onError(Throwable t) throws UnsupportedEncodingException {
            if (inOnError) {
                Exceptions.chuck(t);
            }
            inOnError = true;
            try {
                if (!Dependencies.isProductionMode(deps.getInstance(Settings.class))) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    t.printStackTrace(new PrintStream(out));
                    add(Headers.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8.withCharset(charset));
                    this.setMessage(new String(out.toByteArray(), charset));
                }
                this.setResponseCode(HttpResponseStatus.INTERNAL_SERVER_ERROR);
            } finally {
                inOnError = false;
            }
        }
        private com.mastfrog.acteur.State cachedState;

        Acteur delegate() {
            if (acteur == null) {
                try {
                    acteur = deps.getInstance(type);
                } catch (Exception e) {
                    e.printStackTrace(); //XXX
                    try {
                        deps.getInstance(Application.class).internalOnError(e);
                        onError(e);
                    } catch (UnsupportedEncodingException ex) {
                        Exceptions.chuck(ex);
                    }
                }
            }
            return acteur;
        }

        @Override
        public com.mastfrog.acteur.State getState() {
            return cachedState == null ? cachedState = delegate().getState() : cachedState;
        }

        @Override
        public String toString() {
            return "Wrapper [" + (acteur == null ? type + " (type)" : acteur)
                    + " lastState=" + cachedState + "]";
        }

        @Override
        public Acteur getDelegate() {
            return delegate();
        }
    }
}
