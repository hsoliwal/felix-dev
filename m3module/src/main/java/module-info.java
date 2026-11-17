module com.synexia.felix.modules {
    // Test fixtures use javax.tools; a static requirement avoids a runtime dependency.
    requires static java.compiler;
    exports com.synexia.felix.modules;
}
