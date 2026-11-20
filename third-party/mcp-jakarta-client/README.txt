Vendored copy of https://github.com/HanSolo/jakarta-mcp-client (core module, Apache-2.0), because 0.1.0 is not on Maven Central yet.
The Dockerfile installs it into the local Maven repository before building DuckInsurance.
Once the library is published, delete this folder and the install step in the Dockerfile and use the released version in pom.xml.
