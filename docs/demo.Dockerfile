# VHS plus a JDK (for fieldlog) and strata, so the terminal demo shows the
# export going straight into strata. Built by `make demo`.
FROM rust:1 AS strata
RUN cargo install --locked --git https://github.com/ahmed-hashim-pro/strata --rev 047ca52 strata

FROM ghcr.io/charmbracelet/vhs:v0.10.0
COPY --from=eclipse-temurin:17-jre /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk PATH=/opt/java/openjdk/bin:$PATH
COPY --from=strata /usr/local/cargo/bin/strata /usr/local/bin/strata
