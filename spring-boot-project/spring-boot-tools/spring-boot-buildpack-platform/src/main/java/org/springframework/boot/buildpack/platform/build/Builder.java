/*
 * Copyright 2012-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.buildpack.platform.build;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

import org.springframework.boot.buildpack.platform.docker.DockerApi;
import org.springframework.boot.buildpack.platform.docker.DockerLog;
import org.springframework.boot.buildpack.platform.docker.TotalProgressEvent;
import org.springframework.boot.buildpack.platform.docker.TotalProgressPullListener;
import org.springframework.boot.buildpack.platform.docker.TotalProgressPushListener;
import org.springframework.boot.buildpack.platform.docker.UpdateListener;
import org.springframework.boot.buildpack.platform.docker.configuration.DockerConfiguration;
import org.springframework.boot.buildpack.platform.docker.configuration.ResolvedDockerHost;
import org.springframework.boot.buildpack.platform.docker.transport.DockerEngineException;
import org.springframework.boot.buildpack.platform.docker.type.Binding;
import org.springframework.boot.buildpack.platform.docker.type.Image;
import org.springframework.boot.buildpack.platform.docker.type.ImageArchive;
import org.springframework.boot.buildpack.platform.docker.type.ImagePlatform;
import org.springframework.boot.buildpack.platform.docker.type.ImageReference;
import org.springframework.boot.buildpack.platform.io.IOBiConsumer;
import org.springframework.boot.buildpack.platform.io.TarArchive;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Central API for running buildpack operations.
 *
 * @author Phillip Webb
 * @author Scott Frederick
 * @author Andrey Shlykov
 * @author Rafael Ceccone
 * @since 2.3.0
 */
public class Builder {

	private final BuildLog log;

	private final DockerApi docker;

	private final DockerConfiguration dockerConfiguration;

	/**
	 * Create a new builder instance.
	 */
	public Builder() {
		this(BuildLog.toSystemOut());
	}

	/**
	 * Create a new builder instance.
	 * @param dockerConfiguration the docker configuration
	 * @since 2.4.0
	 */
	public Builder(DockerConfiguration dockerConfiguration) {
		this(BuildLog.toSystemOut(), dockerConfiguration);
	}

	/**
	 * Create a new builder instance.
	 * @param log a logger used to record output
	 */
	public Builder(BuildLog log) {
		this(log, new DockerApi(null, BuildLogAdapter.get(log)), null);
	}

	/**
	 * Create a new builder instance.
	 * @param log a logger used to record output
	 * @param dockerConfiguration the docker configuration
	 * @since 2.4.0
	 */
	public Builder(BuildLog log, DockerConfiguration dockerConfiguration) {
		this(log, new DockerApi((dockerConfiguration != null) ? dockerConfiguration.getHost() : null,
				BuildLogAdapter.get(log)), dockerConfiguration);
	}

	Builder(BuildLog log, DockerApi docker, DockerConfiguration dockerConfiguration) {
		Assert.notNull(log, "'log' must not be null");
		this.log = log;
		this.docker = docker;
		this.dockerConfiguration = dockerConfiguration;
	}

	public void build(BuildRequest request) throws DockerEngineException, IOException {
		Assert.notNull(request, "'request' must not be null");
		this.log.start(request);
		validateBindings(request.getBindings());
		String domain = request.getBuilder().getDomain();
		PullPolicy pullPolicy = request.getPullPolicy();
		ImageFetcher imageFetcher = new ImageFetcher(domain, getBuilderAuthHeader(), pullPolicy,
				request.getImagePlatform());
		Image builderImage = imageFetcher.fetchImage(ImageType.BUILDER, request.getBuilder());
		BuilderMetadata builderMetadata = BuilderMetadata.fromImage(builderImage);
		request = withRunImageIfNeeded(request, builderMetadata);
		Image runImage = imageFetcher.fetchImage(ImageType.RUNNER, request.getRunImage());
		assertStackIdsMatch(runImage, builderImage);
		BuildOwner buildOwner = BuildOwner.fromEnv(builderImage.getConfig().getEnv());
		BuildpackLayersMetadata buildpackLayersMetadata = BuildpackLayersMetadata.fromImage(builderImage);
		Buildpacks buildpacks = getBuildpacks(request, imageFetcher, builderMetadata, buildpackLayersMetadata);
		EphemeralBuilder ephemeralBuilder = new EphemeralBuilder(buildOwner, builderImage, request.getName(),
				builderMetadata, request.getCreator(), request.getEnv(), buildpacks);
		executeLifecycle(request, ephemeralBuilder);
		tagImage(request.getName(), request.getTags());
		if (request.isPublish()) {
			pushImages(request.getName(), request.getTags());
		}
	}

	private void validateBindings(List<Binding> bindings) {
		for (Binding binding : bindings) {
			if (binding.usesSensitiveContainerPath()) {
				this.log.sensitiveTargetBindingDetected(binding);
			}
		}
	}

	private BuildRequest withRunImageIfNeeded(BuildRequest request, BuilderMetadata metadata) {
		if (request.getRunImage() != null) {
			return request;
		}
		return request.withRunImage(getRunImageReference(metadata));
	}

	private ImageReference getRunImageReference(BuilderMetadata metadata) {
		if (metadata.getRunImages() != null && !metadata.getRunImages().isEmpty()) {
			String runImageName = metadata.getRunImages().get(0).getImage();
			return ImageReference.of(runImageName).inTaggedOrDigestForm();
		}
		String runImageName = metadata.getStack().getRunImage().getImage();
		Assert.state(StringUtils.hasText(runImageName), "Run image must be specified in the builder image metadata");
		return ImageReference.of(runImageName).inTaggedOrDigestForm();
	}

	private void assertStackIdsMatch(Image runImage, Image builderImage) {
		StackId runImageStackId = StackId.fromImage(runImage);
		StackId builderImageStackId = StackId.fromImage(builderImage);
		if (runImageStackId.hasId() && builderImageStackId.hasId()) {
			Assert.state(runImageStackId.equals(builderImageStackId), () -> "Run image stack '" + runImageStackId
					+ "' does not match builder stack '" + builderImageStackId + "'");
		}
	}

	private Buildpacks getBuildpacks(BuildRequest request, ImageFetcher imageFetcher, BuilderMetadata builderMetadata,
			BuildpackLayersMetadata buildpackLayersMetadata) {
		BuildpackResolverContext resolverContext = new BuilderResolverContext(imageFetcher, builderMetadata,
				buildpackLayersMetadata);
		return BuildpackResolvers.resolveAll(resolverContext, request.getBuildpacks());
	}

	private void executeLifecycle(BuildRequest request, EphemeralBuilder builder) throws IOException {
		try (Lifecycle lifecycle = new Lifecycle(this.log, this.docker, getDockerHost(), request, builder)) {
			executeLifecycle(builder, lifecycle);
		}
	}

	private void executeLifecycle(EphemeralBuilder builder, Lifecycle lifecycle) throws IOException {
		ImageArchive archive = builder.getArchive(lifecycle.getApplicationDirectory());
		this.docker.image().load(archive, UpdateListener.none());
		try {
			lifecycle.execute();
		}
		finally {
			this.docker.image().remove(builder.getName(), true);
		}
	}

	private ResolvedDockerHost getDockerHost() {
		boolean bindHostToBuilder = this.dockerConfiguration != null && this.dockerConfiguration.isBindHostToBuilder();
		return (bindHostToBuilder) ? ResolvedDockerHost.from(this.dockerConfiguration.getHost()) : null;
	}

	private void tagImage(ImageReference sourceReference, List<ImageReference> tags) throws IOException {
		for (ImageReference tag : tags) {
			this.docker.image().tag(sourceReference, tag);
			this.log.taggedImage(tag);
		}
	}

	private void pushImages(ImageReference name, List<ImageReference> tags) throws IOException {
		pushImage(name);
		for (ImageReference tag : tags) {
			pushImage(tag);
		}
	}

	private void pushImage(ImageReference reference) throws IOException {
		Consumer<TotalProgressEvent> progressConsumer = this.log.pushingImage(reference);
		TotalProgressPushListener listener = new TotalProgressPushListener(progressConsumer);
		this.docker.image().push(reference, listener, getPublishAuthHeader());
		this.log.pushedImage(reference);
	}

	private String getBuilderAuthHeader() {
		return (this.dockerConfiguration != null && this.dockerConfiguration.getBuilderRegistryAuthentication() != null)
				? this.dockerConfiguration.getBuilderRegistryAuthentication().getAuthHeader() : null;
	}

	private String getPublishAuthHeader() {
		return (this.dockerConfiguration != null && this.dockerConfiguration.getPublishRegistryAuthentication() != null)
				? this.dockerConfiguration.getPublishRegistryAuthentication().getAuthHeader() : null;
	}

	/**
	 * Internal utility class used to fetch images.
	 */
	private class ImageFetcher {

		private final String domain;

		private final String authHeader;

		private final PullPolicy pullPolicy;

		private ImagePlatform defaultPlatform;

		ImageFetcher(String domain, String authHeader, PullPolicy pullPolicy, ImagePlatform platform) {
			this.domain = domain;
			this.authHeader = authHeader;
			this.pullPolicy = pullPolicy;
			this.defaultPlatform = platform;
		}

		Image fetchImage(ImageType type, ImageReference reference) throws IOException {
			Assert.notNull(type, "'type' must not be null");
			Assert.notNull(reference, "'reference' must not be null");
			Assert.state(this.authHeader == null || reference.getDomain().equals(this.domain),
					() -> String.format("%s '%s' must be pulled from the '%s' authenticated registry",
							StringUtils.capitalize(type.getDescription()), reference, this.domain));
			if (this.pullPolicy == PullPolicy.ALWAYS) {
				return checkPlatformMismatch(pullImage(reference, type), reference);
			}
			try {
				return checkPlatformMismatch(Builder.this.docker.image().inspect(reference), reference);
			}
			catch (DockerEngineException ex) {
				if (this.pullPolicy == PullPolicy.IF_NOT_PRESENT && ex.getStatusCode() == 404) {
					return checkPlatformMismatch(pullImage(reference, type), reference);
				}
				throw ex;
			}
		}

		private Image pullImage(ImageReference reference, ImageType imageType) throws IOException {
			TotalProgressPullListener listener = new TotalProgressPullListener(
					Builder.this.log.pullingImage(reference, this.defaultPlatform, imageType));
			Image image = Builder.this.docker.image().pull(reference, this.defaultPlatform, listener, this.authHeader);
			Builder.this.log.pulledImage(image, imageType);
			if (this.defaultPlatform == null) {
				this.defaultPlatform = ImagePlatform.from(image);
			}
			return image;
		}

		private Image checkPlatformMismatch(Image image, ImageReference imageReference) {
			if (this.defaultPlatform != null) {
				ImagePlatform imagePlatform = ImagePlatform.from(image);
				if (!imagePlatform.equals(this.defaultPlatform)) {
					throw new PlatformMismatchException(imageReference, this.defaultPlatform, imagePlatform);
				}
			}
			return image;
		}

	}

	private static final class PlatformMismatchException extends RuntimeException {

		private PlatformMismatchException(ImageReference imageReference, ImagePlatform requestedPlatform,
				ImagePlatform actualPlatform) {
			super("Image platform mismatch detected. The configured platform '%s' is not supported by the image '%s'. Requested platform '%s' but got '%s'"
				.formatted(requestedPlatform, imageReference, requestedPlatform, actualPlatform));
		}

	}

	/**
	 * A {@link DockerLog} implementation that adapts to an {@link AbstractBuildLog}.
	 */
	static final class BuildLogAdapter implements DockerLog {

		private final AbstractBuildLog log;

		private BuildLogAdapter(AbstractBuildLog log) {
			this.log = log;
		}

		@Override
		public void log(String message) {
			this.log.log(message);
		}

		/**
		 * Creates {@link DockerLog} instance based on the provided {@link BuildLog}.
		 * <p>
		 * If the provided {@link BuildLog} instance is an {@link AbstractBuildLog}, the
		 * method returns a {@link BuildLogAdapter}, otherwise it returns a default
		 * {@link DockerLog#toSystemOut()}.
		 * @param log the {@link BuildLog} instance to delegate
		 * @return a {@link DockerLog} instance for logging
		 */
		static DockerLog get(BuildLog log) {
			if (log instanceof AbstractBuildLog abstractBuildLog) {
				return new BuildLogAdapter(abstractBuildLog);
			}
			return DockerLog.toSystemOut();
		}

	}

	/**
	 * {@link BuildpackResolverContext} implementation for the {@link Builder}.
	 */
	private class BuilderResolverContext implements BuildpackResolverContext {

		private final ImageFetcher imageFetcher;

		private final BuilderMetadata builderMetadata;

		private final BuildpackLayersMetadata buildpackLayersMetadata;

		BuilderResolverContext(ImageFetcher imageFetcher, BuilderMetadata builderMetadata,
				BuildpackLayersMetadata buildpackLayersMetadata) {
			this.imageFetcher = imageFetcher;
			this.builderMetadata = builderMetadata;
			this.buildpackLayersMetadata = buildpackLayersMetadata;
		}

		@Override
		public List<BuildpackMetadata> getBuildpackMetadata() {
			return this.builderMetadata.getBuildpacks();
		}

		@Override
		public BuildpackLayersMetadata getBuildpackLayersMetadata() {
			return this.buildpackLayersMetadata;
		}

		@Override
		public Image fetchImage(ImageReference reference, ImageType imageType) throws IOException {
			return this.imageFetcher.fetchImage(imageType, reference);
		}

		@Override
		public void exportImageLayers(ImageReference reference, IOBiConsumer<String, TarArchive> exports)
				throws IOException {
			Builder.this.docker.image().exportLayers(reference, exports);
		}

	}

}
