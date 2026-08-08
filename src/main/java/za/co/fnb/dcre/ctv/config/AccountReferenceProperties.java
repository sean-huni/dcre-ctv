package za.co.fnb.dcre.ctv.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Where the account reference artifact is and which version of it THIS service expects.
 *
 * <p><b>The consumer declares the version it wants.</b> There is no "pick the newest
 * directory" behaviour, because that is a fail-open: a directory appearing beside the
 * expected one would silently change what every verdict is judged against. The loader
 * never guesses, and a manifest whose {@code dataset.version} differs from
 * {@link #datasetVersion()} FAILS.
 *
 * <p><b>{@link #maxAge()} has NO default and is DELIBERATELY unset in the committed yml.</b>
 * The freshness contract belongs to A-4, which has not landed. The check is wired and
 * INERT: while this is null the loader logs, at INFO, on every run, that freshness is not
 * being enforced, and proceeds. When A-4 supplies a number it is set here and the gate
 * arms with no code change. A number invented in the meantime, in code, in yml or in a
 * test resource default, would be an unowned production rule wearing the costume of a
 * decision.
 *
 * @param root           directory holding one sub-directory per dataset version
 * @param datasetVersion the sub-directory name, and the version the manifest must declare
 * @param maxAge         freshness limit measured against {@code publication.ts}; null means inert
 */
@ConfigurationProperties(prefix = "dcre.ctv.reference.account")
public record AccountReferenceProperties(String root, String datasetVersion, Duration maxAge) {
}
