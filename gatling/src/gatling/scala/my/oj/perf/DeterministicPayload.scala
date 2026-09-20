package my.oj.perf

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * The payloads a reproducible run submits, as pure functions of `(user, submission index)`.
 *
 * Separate from `ApiLoad` on purpose. Everything in `ApiLoad` is a Gatling DSL value, so touching
 * the object at all starts Gatling's runtime and the object can only be used from inside a
 * simulation - which means a function living there cannot be checked outside a run. These functions
 * have no dependency beyond the JDK, so their one property that matters can be demonstrated: call
 * them twice and get the same answer.
 *
 * That property is what a recovery measurement rests on. A run that draws its payloads at random
 * submits different sources from every other run, so the judge reaches different verdicts, the
 * contest ends on different scores, and two runs' recovery times describe two different contests.
 * With the payload fixed by the pair, the data is the same in every run and the only thing a run
 * varies is when each submission arrived.
 */
object DeterministicPayload {

  /**
   * The source the nth submission of a user carries.
   *
   * The index is in the source on purpose. Two submissions from one user have to differ or the
   * product's dedup would fold them together, and putting the index there rather than a random token
   * is what makes the pair `(user, nth submission)` name the source, and so the verdict, in advance.
   */
  def code(tag: String, userName: String, submissionIndex: Int): String =
    s"// $tag-$userName-$submissionIndex%0Aint main(){return 0;}"

  /**
   * The problem the nth submission of a user goes to.
   *
   * Spread by hash rather than by `index % problemCount`: modulo would give every user the same
   * problem order, so a rollback that removes a contiguous tail of a user's submissions would take
   * away a systematically different mix of problems than a rollback elsewhere - and the lost tail is
   * the experiment's independent variable, which must not also be moving the composition of what was
   * lost. The seed is the user and the index alone, so the same pair names the same problem in every
   * run.
   *
   * Hashed separately from the source rather than from a shared digest. One digest would tie which
   * problem was attempted to whether the attempt was accepted, and a judge whose verdicts correlate
   * with the problem a user chose is not a judge.
   */
  def problemId(problemIdStart: Long, problemIdEnd: Long,
                userName: String, submissionIndex: Int): Long = {
    val problemCount = problemIdEnd - problemIdStart + 1L
    problemIdStart + Math.floorMod(digestLong(s"$userName#$submissionIndex"), problemCount)
  }

  /**
   * The first eight bytes of the SHA-256 of the seed, as a signed long.
   *
   * SHA-256 rather than `String.hashCode`: the latter is specified and stable, so it would do, but it
   * is a polynomial over 16-bit characters that a naming convention as regular as
   * `<prefix>_user_<n>` drives into adjacent buckets. A digest spreads any seed, and the residual
   * bias of reducing a uniform 64-bit draw modulo a problem count in the low tens is far below
   * anything a run of this size can resolve.
   */
  private def digestLong(seed: String): Long = {
    val digest = MessageDigest.getInstance("SHA-256")
    ByteBuffer.wrap(digest.digest(seed.getBytes(StandardCharsets.UTF_8))).getLong
  }
}
