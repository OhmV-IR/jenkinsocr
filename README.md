# jenkinsocr

## Introduction

TODO Describe what your plugin does here

## Requirements

- Jenkins running on **Java 25**.
- To accept HEIC/HEIF photos (the iPhone default), **libheif** with its HEVC decoder must be installed on the
  Jenkins controller, e.g. on Debian/Ubuntu:

  ```sh
  apt-get install libheif-dev libheif-plugin-libde265
  ```

  libheif is loaded through Java's foreign function API. Add `--enable-native-access=ALL-UNNAMED` to the
  controller's JVM options to silence the native-access warning. Without libheif, JPEG/PNG photos still work and
  HEIC uploads fail with an error explaining what to install.

## Getting started

TODO Tell users how to configure your plugin here, include screenshots, pipeline examples and 
configuration-as-code examples.

## Issues

TODO Decide where you're going to host your issues, the default is Jenkins JIRA, but you can also enable GitHub issues,
If you use GitHub issues there's no need for this section; else add the following line:

Report issues and enhancements in the [Jenkins issue tracker](https://issues.jenkins.io/).

## Contributing

TODO review the default [CONTRIBUTING](https://github.com/jenkinsci/.github/blob/master/CONTRIBUTING.md) file and make sure it is appropriate for your plugin, if not then add your own one adapted from the base file

Refer to our [contribution guidelines](https://github.com/jenkinsci/.github/blob/master/CONTRIBUTING.md)

## LICENSE

Licensed under MIT, see [LICENSE](LICENSE.md)

