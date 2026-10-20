// jxlatte, a JPEG XL decoder in plain Java, for the DNGs a Galaxy writes in Expert RAW. See README.md.
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    // Upstream's code, kept as it is apart from latent.patch; its warnings are not ours to fix.
    options.compilerArgs.add("-nowarn")
}
