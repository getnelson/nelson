//: ----------------------------------------------------------------------------
//: Copyright (C) 2017 Verizon.  All Rights Reserved.
//:
//:   Licensed under the Apache License, Version 2.0 (the "License");
//:   you may not use this file except in compliance with the License.
//:   You may obtain a copy of the License at
//:
//:       http://www.apache.org/licenses/LICENSE-2.0
//:
//:   Unless required by applicable law or agreed to in writing, software
//:   distributed under the License is distributed on an "AS IS" BASIS,
//:   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//:   See the License for the specific language governing permissions and
//:   limitations under the License.
//:
//: ----------------------------------------------------------------------------
package nelson

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration

/**
 * Main public entry point for creating in-memory cache.
 *
 * Default cache has no means to remove anything. It keeps entries forever (but they can be overriden)
 *
 * Behavior can be customized with following options:
 *    - limit maximum number of elements to cache
 *      once limit is reached older elements will be removed, typically using "last recently used" strategy
 *    - "ttl since update" for object in cache
 *      Prevents from keeping stale objects in the cache
 *    - "ttl since last use"
 *      Prevents from wasting memory on unused objects
 *
 * ConcurrencyLevel hint is Guava-specific hint to guide the allowed concurrency among update operations.
 * Default value is 4 (for guava 13.0)
 *
 * From Guava docs:
 *   Used as a hint for internal sizing. The table is internally partitioned to try to permit the indicated number of
 *   concurrent updates without contention. Because assignment of entries to these partitions is not necessarily uniform,
 *   the actual concurrency observed may vary. Ideally, you should choose a value to accommodate as many threads as will
 *   ever concurrently modify the table. Using a significantly higher value than you need can waste space and time,
 *   and a significantly lower value can lead to thread contention. But overestimates and underestimates within an order
 *   of magnitude do not usually have much noticeable impact. A value of one permits only one thread to modify the cache
 *   at a time, but since read operations and cache loading computations can proceed concurrently, this still yields
 *   higher concurrency than full synchronization.
 */
object Cache {
  def apply[K, V](maximumSize: Option[Int] = None,
                  concurrencyLevel: Option[Int] = None,
                  expireAfterWrite: Option[Duration] = None,
                  expireAfterAccess: Option[Duration] = None): Cache[K, V] =
    (new GuavaCacheBuilder[K, V]()).
      maximumSize(maximumSize).
      concurrencyLevel(concurrencyLevel).
      expireAfterAccess(expireAfterAccess).
      expireAfterWrite(expireAfterWrite).build()
}

/**
 * Basic cache API providing get/put operations
 */
trait Cache[K, V] {
  def get(key: K): Option[V]
  def put(key: K, value: V): Unit
  def invalidate(key: K): Unit
}

/**
 * Basic CacheBuilder API for constructing cache instances.
 * API is subset of what Google's Guava libraries is providing
 */
trait CacheBuilder[K, V] {
  def build(): Cache[K, V]

  def maximumSize(n: Int): CacheBuilder[K, V]
  def concurrencyLevel(n: Int): CacheBuilder[K, V]
  def expireAfterWrite(duration: Duration): CacheBuilder[K, V]
  def expireAfterAccess(duration: Duration): CacheBuilder[K, V]
}

/**************** Specific Guava based cache implementation ******************/

class GuavaCache[K, V](private val cache:com.google.common.cache.Cache[K,V]) extends Cache[K, V] {
  def get(key: K):Option[V] = Option(cache.getIfPresent(key))
  def put(key: K, value:V) = cache.put(key, value)
  def invalidate(key: K) = cache.invalidate(key)

}

class GuavaCacheBuilder[K, V] extends CacheBuilder[K, V] {
  val builder:com.google.common.cache.CacheBuilder[K, V] =
    com.google.common.cache.CacheBuilder.newBuilder().asInstanceOf[com.google.common.cache.CacheBuilder[K, V]]

  def build(): Cache[K, V] = new GuavaCache[K, V](builder.build[K, V]())

  def maximumSize(n: Int) = new GuavaCacheBuilder[K, V] {
    override val builder = GuavaCacheBuilder.this.builder.maximumSize(n.toLong)
  }

  def concurrencyLevel(n: Int) = new GuavaCacheBuilder[K, V] {
    override val builder = GuavaCacheBuilder.this.builder.concurrencyLevel(n)
  }

  def expireAfterWrite(duration: Duration) = new GuavaCacheBuilder[K, V] {
    override val builder = GuavaCacheBuilder.this.builder.expireAfterWrite(duration.toMicros, TimeUnit.MICROSECONDS)
  }

  def expireAfterAccess(duration: Duration) = new GuavaCacheBuilder[K, V] {
    override val builder = GuavaCacheBuilder.this.builder.expireAfterAccess(duration.toMicros, TimeUnit.MICROSECONDS)
  }

  /**
   * Helper methods to deal with optional parameters
   */

  def maximumSize(n: Option[Int]): GuavaCacheBuilder[K,V] =
    n.map {v => maximumSize(v)}.getOrElse(this)

  def concurrencyLevel(n: Option[Int]): GuavaCacheBuilder[K,V] =
    n.map {v => concurrencyLevel(v)}.getOrElse(this)

  def expireAfterWrite(duration: Option[Duration]): GuavaCacheBuilder[K,V] =
    duration.map(expireAfterWrite).getOrElse(this)

  def expireAfterAccess(duration: Option[Duration]): GuavaCacheBuilder[K,V]  =
    duration.map(expireAfterAccess).getOrElse(this)
}
