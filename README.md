[about]: https://github.com/resilience4clj/resilience4clj-circuitbreaker/blob/master/docs/ABOUT.md
[breaker]: https://github.com/resilience4clj/resilience4clj-circuitbreaker/
[circleci-badge]: https://circleci.com/gh/resilience4clj/resilience4clj-cache.svg?style=shield&circle-token=e366fa2eedef2d2163ec81cc00d315137bc0adb2
[circleci]: https://circleci.com/gh/resilience4clj/resilience4clj-cache
[clojars-badge]: https://img.shields.io/clojars/v/resilience4clj/resilience4clj-cache.svg
[clojars]: http://clojars.org/resilience4clj/resilience4clj-cache
[github-issues]: https://github.com/resilience4clj/resilience4clj-cache/issues
[license-badge]: https://img.shields.io/badge/license-MIT-blue.svg
[license]: ./LICENSE
[retry]: https://github.com/resilience4clj/resilience4clj-retry/
[status-badge]: https://img.shields.io/badge/project%20status-alpha-brightgreen.svg

# Resilience4Clj Cache

[![CircleCI][circleci-badge]][circleci]
[![Clojars][clojars-badge]][clojars]
[![License][license-badge]][license]
![Status][status-badge]

Resilience4clj is a lightweight fault tolerance library set built on
top of GitHub's Resilience4j and inspired by Netflix Hystrix. It was
designed for Clojure and functional programming with composability in
mind.

Read more about the [motivation and details of Resilience4clj
here][about].

Resilience4Clj Cache lets you decorate a function call with a
distributed caching infrastructure as provided by any `javax.cache`
(JSR107) provider. The resulting function will behave as an advanced
form of memoization (think of it as distributed memoization with
monitoring and metrics).

## Table of Contents

* [Getting Started](#getting-started)
* [Cache Settings](#cache-settings)
* [Fallback Strategies](#fallback-strategies)
* [Manual Cache Manipulation](#manual-cache-manipulation)
* [Invalidating the Cache](#invalidating-the-cache)
* [Using as an Effect](#using-as-an-effect)
* [Metrics](#metrics)
* [Events](#events)
* [Exception Handling](#exception-handling)
* [Composing Further](#composing-further)
* [Bugs](#bugs)
* [Help!](#help)

## Getting Started

Add `resilience4clj/resilience4clj-cache` as a dependency to your
`deps.edn` file:

``` clojure
resilience4clj/resilience4clj-cache {:mvn/version "0.1.0"}
```

If you are using `lein` instead, add it as a dependency to your
`project.clj` file:

``` clojure
[resilience4clj/resilience4clj-cache "0.1.0"]
```

Resilience4clj cache does depends on a concrete implementation of a
caching engine to the JSR107 interfaces. Therefore, in order to use
Resilience4clj cache you need to choose a compatible caching engine.

This is a far from comprehensive list of options:

- [Cache2k](https://cache2k.org): simple embedded, in-memory cache system
- [Ehcache](http://www.ehcache.org): supports supports offheap storage
  and distributed, persistence via Terracotta
- [Infinispan](http://infinispan.org/): embedded caching as well as
  advanced functionality such as transactions, events, querying,
  distributed processing, off-heap and geographical failover.
- [Redisson](https://redisson.org): Redis Java client in-Memory data
  grid
- [Apache Ignite](https://ignite.apache.org): memory-centric
  distributed database, caching, and processing platform for
  transactional, analytical, and streaming workloads delivering
  in-memory speeds at petabyte scale

For this getting started let's use a simple embedded, in-memory cache
via Infinispan. Add it as a dependency to your `deps.edn` file:

``` clojure
org.infinispan/infinispan-embedded {:mvn/version "9.1.7.Final"}
```

Or, if you  are using `lein` instead,  add it as a  dependency to your
`project.clj` file:

``` clojure
[org.infinispan/infinispan-embedded "9.1.7.Final"]
```

Once both Resilience4clj cache and a concrete cache engine in place
you can require the library:

``` clojure
(require '[resilience4clj-cache.core :as c])
```

Then create a cache calling the function `create`:

``` clojure
(def cache (c/create "my-cache"))
```

Now you can decorate any function you have with the cache you just
defined.

For the sake of this example, let's create a function that takes
1000ms to return:

``` clojure
(defn slow-hello []
  (Thread/sleep 1000)
  "Hello World!")
```

You can now create a decorated version of your `slow-hello` function
above combining the `cache` we created before like this:

``` clojure
(def protected (c/decorate slow-hello cache))
```

When you call `protected` for the first time it will take around
1000ms to run because of the timeout we added there. Subsequent calls
will return virtually instanteneaously because the return of the
function has been cached in memory.

``` clojure
(time (protected))
"Elapsed time: 1001.462526 msecs"
Hello World!

(time (protected))
"Elapsed time: 1.238522 msecs"
Hello World!
```

By default the `create` function will set your cache as eternal so
every single call to `protected` above will return `"Hello World!"`
for long as the cache entry is in memory (or until the cache is
manually invalidated - see function `invalidate!` below).

## Cache Settings

If you simply call the `create` function providing a cache name,
Resilience4clj cache will capture the default caching provider from
your classpath and then use sensible and simple settings to bring your
cache system up. These steps should cover many of the basic caching
scenarios.

The `create` supports a second map argument for further
configurations.

There are two very basic fine-tuning settings available:

1. `:eternal?` - whether this cache will retain its entries forever or
   not. Caching engines might still discard entries if certain
   conditions are met (i.e. full memory) so this should be used as an
   indication of intent more than a solid dependency. Default `true`.
2. `:expire-after` - if you don't want an eternal cache entry, chances
   are you would prefer entries that expire after a certain amount of
   time. You can specify any amount of milliseconds of at least 1000
   or higher (if specified, `:eternal?` is automatically turned off).

For more advanced scenarios, you might want to set up your caching
engine with all sorts of whistles and belts. In these scenarios you
will need to provide a combination of factory functions to cover for
your particular need:

1. `:provider-fn` - function that receives the options map sent to
   `create` and must return a concrete implementation of a
   `javax.cache.spi.CachingProvider`. If `:provider-fn` is not
   specified, Resilience4clj will simply get the default caching
   provider on your clasppath.
2. `:manager-fn` - function that receives the `CachingProvider` as a
   first argument and the options map sent to `create` as the second
   one and must return a concrete implementation of a
   `CacheManager`. If `:manager-fn` is not specified, Resilience4clj
   will simply ask the provider for its default `CacheManager`.
3. `:config-fn` - function that receives the options maps sent to
   `create` and must return any concrete implementation of
   `javax.cache.configuration.Configuration`. If `:config-fn` is not
   specified, Resilience4clj will create a `MutableConfiguration` and
   use `:eternal?` and `:expire-after` above to do some basic fine
   tuning on the config.

Things to notice when setting up your cache using these factory
functions above:

* Among many other impactful settings, your expiration policies will
  definitely affect the way that your cache behaves.
* If your configuration does not expose mutable abilities such as the
  method `registerCacheEntryListener`, then listening the expiration
  events as documented in the [Events](#events) section is not going
  to work.
* Resilience4clj cache expects the `<K, V>` of the Cache to be
  `java.lang.String, java.lang.Object`. Other settings have not been
  tested and might not work.

Here is an example creating a cache that expires in a minute:

``` clojure
(def cache (c/create {:expire-after 60000}))
```

The function `config` returns the configuration of a cache in case
you need to inspect it. Example:

``` clojure
(c/config cache)
=> {:provider-fn #object[resilience4clj-cache.core$get-provider...
    :manager-fn #object[resilience4clj-cache.core$get-manager...
    :config-fn #object[resilience4clj-cache.core$get-config...
    :eternal? true
    :expire-after nil}
```

## Fallback Strategies

When decorating your function with a cache you can opt to have a
fallback function. This function will be called instead of an
exception being thrown when the call would fail (its traditional
throw). This feature can be seen as an obfuscation of a try/catch to
consumers.

This is particularly useful if you want to obfuscate from consumers
that the external dependency failed. Example:

``` clojure
(def cache (c/create "my-cache"))

(defn hello [person]
  ;; hypothetical flaky, external HTTP request
  (str "Hello " person))

(def cached-hello
  (c/decorate hello
              {:fallback (fn [e person]
                           (str "Hello from fallback to " person))}))
```

The signature of the fallback function is the same as the original
function plus an exception as the first argument (`e` on the example
above). This exception is an `ExceptionInfo` wrapping around the real
cause of the error. You can inspect the `:cause` node of this
exception to learn about the inner exception:

``` clojure
(defn fallback-fn [e]
  (str "The cause is " (-> e :cause)))
```

For more details on [Exception Handling](#exception-handling) see the
section below.

When considering fallback strategies there are usually three major
strategies:

1. **Failure**: the default way for Resilience4clj - just let the
   exceptiohn flow - is called a "Fail Fast" approach (the call will
   fail fast once the breaker is open). Another approach is "Fail
   Silently". In this approach the fallback function would simply hide
   the exception from the consumer (something that can also be done
   conditionally).
2. **Content Fallback**: some of the examples of content fallback are
   returning "static content" (where a failure would always yield the
   same static content), "stubbed content" (where a failure would
   yield some kind of related content based on the paramaters of the
   call), or "cached" (where a cached copy of a previous call with the
   same parameters could be sent back).
3. **Advanced**: multiple strategies can also be combined in order to
   create even better fallback strategies.

## Manual Cache Manipulation

By default Resilience4clj cache can be used as a decorator to your
external calls and it will take care of basic caching for you. In some
circumstances though you might want to interact directly with its
cache. One such situation is when [using the cache as an
effect](#using-as-an-effect).

There are three functions to directly manipulate the cache:

1. `(put! <cache> <args> <value>)`: will put the `<value>` in
   `<cache>` keyed by `<args>`
2. `(get <cache> <args>)`: will get the cached value from `<cache>`
   keyed by `<args>`
3. `(contains? <cache> <args>)`: convenience check whether the entry
   keyed by `<args>` is in the `<cache>`

`<args>` can be any Clojure object that supports `.toString`.

Caveats when manually using the cache:

1. You are not using any of the automatic, decorated features of the
   cache - therefore you've got no fallback for instance
2. Resilience4clj cache internally segments the cache for every
   function that it decorates and every combination of arguments sent
   to the function. When used manually, only one "caching space" is
   used. Therefore, if the same args are used in different places with
   different semantic meanings you will still get the same values from
   the cache.
3. The `put!` and `get` interfaces prefer dealing with `<args>` as a
   list. If you don send a `seqable?` as `<args>`, whatever parameter
   you send will be transformed into a list. Therefore (due to the
   bullet above) sending `:foobar` is equivalent to `'(:foobar)`

See [using the cache as an effect](#using-as-an-effect) for a use case
where direct manipulation of the cache is very useful.

## Invalidating the Cache

By default Resilience4clj cache uses an eternal cache (this can be
[set up differently if you want](#cache-settings)) therefore, you
might eventually want to invalidate the cache altogether.

In order to do so, use the function `invalidate!`. In the following
code, the `cache` will be invalidated:

``` clojure
(c/invalidate! cache)
```

## Using as an Effect

Resilience4clj cache is a great alternative for creating fallback
strategies in conjunction with other Resilience4clj libraries.

Some libraries like [Resilience4clj retry][retry] and [Resilience4clj
circuit breaker][breaker] have a feature called _effects_ for
capturing side-effects. In this context, a side-effect is a handler
for processing the successful output of the decorated function call.

For instance, assuming that you have required
`resilience4clj-retry.core` as `r` and `resilience4clj-retry.core` as
`c`:

``` clojure
(def retry (r/create "hello-retry"))

(def cache (c/create "hello-cache"))

(defn hello [person]
  ;; hypothetical flaky, external HTTP request
  (str "Hello " person "!!"))
```

Now that you have a default `retry`, a default `cache`, and a
potentially flaky function `hello` let's create an effect that puts
the returned value in the cache, a fallback that gets it from the
cache and a decorated function that puts them together:

``` clojure
(defn effect-fn
  [ret person]
  (c/put! cache person ret))

(defn fallback-fn
  [e person]
  (c/get cache person))

(def safe-cached-hello
  (r/decorate hello retry
              {:effect effect-fn
               :fallback fallback-fn}))
```

The behavior here is that when calling the `safe-cached-hello`
function, the function `hello` will be retried for a few times (max
default is 3). In case of success, the returned value will be put in
the cache. In case of failure whatever value is on the cache will be
returned.

Of course, this is a very naive approach as it will simply return `nil` in
a failure scenario where the cache is empty. A more advanced approach
would be:

``` clojure
(defn fallback-fn
  [e person]
  (if (c/contains? cache person)
    (c/get cache person)
    (throw e)))
```

In the example above, if the the entry for `person` is still not in
place, the underlying expression is thrown.

By combining several modules from Resilience4clj (see [the list
here][about]) you can achieve very advanced behavior quickly. For
instance:

``` clojure
(def very-safe-hello
  (-> hello
      (r/decorate retry {:effect effect-fn
                         :fallback fallback-fn})
      (tl/decorate timelimiter)
      (cb/decorate breaker)))
```

With the snippet above, you are retrying `hello` in case of failure,
caching its return when succesful, having a cached fallback strategy,
within a pre-defined time limit (execution budget) and protected by a
ring-based circuit breaker.

All that in just 5 lines.

## Metrics

The function `metrics` returns a map with the metrics of the cache:

``` clojure
(c/metrics cache)

=> {:hits 0
    :misses 0
    :errors 0
    :manual-puts 0 
    :manual-gets 0}
```

The nodes should be self-explanatory. Because direct manipulation of
the cache does not go through the automatic hit/miss logic, these are
kept separatelly.

The metrics can be reset with a call to the `reset!` function:

``` clojure
(c/reset! cache)
```

Metrics will cycle back to 0 when they reach `Long/MAX_VALUE`.

## Events

You can listen to events generated by the use of the cache. This is
particularly useful for logging, debugging, or monitoring the health
of your cache.

``` clojure
(def cache (c/create "my-cache"))

(c/listen-event cache
                 :HIT
                 (fn [evt]
                   (println (str "Your cache has been hit"))))
```

There are six types of events:

1. `:HIT` - informs that a call has hit the cache
2. `:MISSED` - informs that a call has missed the cache
3. `:ERROR` - informs that an error has taken place on the call
4. `:EXPIRED` - informs that an entry has expired on the cache
5. `:MANUAL-PUT` - informs that a manual put has happened
6. `:MANUAL-GET` - informs that a manual get has happened

Notice you have to listen to a particular type of event by specifying
the event-type you want to listen.

**Note on `:EXPIRED`**: expiration rules differ from caching provider
to caching provider. They might also differ depending on the way you
have set up your cache (see [cache settings](#cache-settings) for more
details). In every practical sense, you should not rely on `:EXPIRED`
for anything business critical unless the behavior of your
cache/settings is known and consistent.

All events receive a map containing the `:event-type`, the
`:cache-name`, the event `:creation-time`, the function name that
generated the entry `:fn-name`, and the internal `:key` that
represents the unique id of the cache entry. For now, `:EXPIRED` does
not support `:fn-name`.

## Exception Handling

When using the fallback function, be aware that its signature is the
same as the original function plus an exception (`e` on the example
above). This exception is an `ExceptionInfo` wrapping around the real
cause of the error. You can inspect the `:cause` node of this
exception to learn about the inner exception:

If you are not using a fallback function, then you don't need to worry
about anything. Your exception will bubble up as you would expect.

## Composing Further

Resilience4clj is composed of [several modules][about] that easily
compose together. For instance, if you are also using the [retry
module][retry] and assuming your import and basic settings look like
this:

``` clojure
(ns my-app
  (:require [resilience4clj-cache.core :as c]
            [resilience4clj-retry.core :as r]))

;; create a retry with default settings
(def retry (r/create "my-retry"))

;; create cache with default settings
(def cache (c/create "my-cache"))

;; flaky function you want to potentially retry
(defn flaky-hello []
  ;; hypothetical request to a flaky server that might fail (or not)
  "Hello World!")
```

Then you can create a protected call that combines both the retry and
the cache:

``` clojure
(def protected-hello (-> flaky-hello
                         (r/decorate retry)
                         (c/decorate cache)))
```

The resulting function `protected-hello` will retry before persisting
to cache and skip retries altogether in case of cache hits as you
would expect. The composing order makes a big difference of course, if
retry and cache had been reversed here, `flaky-hello` would cache
first and the retry would wrap the cache which is not what you would
want.

The cache module is special as it composes very nicely with a
effect/fallback strategy. See [how to use cache as an
effect](#using-as-an-effect).

## Bugs

If you find a bug, submit a [Github issue][github-issues].

## Help

This project is looking for team members who can help this project
succeed!  If you are interested in becoming a team member please open
an issue.

## License

Copyright © 2019 Tiago Luchini

Distributed under the MIT License.
