# Pinned multi-arch digest for docker.io/library/ubuntu:22.04
# Resolve/update with:
#   docker buildx imagetools inspect ubuntu:22.04
FROM ubuntu:22.04@sha256:ce4a593b4e323dcc3dd728e397e0a866a1bf516a1b7c31d6aa06991baec4f2e0

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
    bash \
    ca-certificates \
    curl \
    file \
    git \
    ninja-build \
    gnupg \
    g++ \
    make \
    pkg-config \
    python3 \
    python3-distutils \
    rsync \
    unzip \
    xz-utils \
    && rm -rf /var/lib/apt/lists/*

RUN ln -sf /usr/bin/python3 /usr/bin/python

WORKDIR /workspace/webrtc-apm
