[![Build Status](https://builds.is-land.com.tw/buildStatus/icon?job=PostCommit-OHARA)](https://builds.is-land.com.tw/job/PostCommit-OHARA/)
[![Documentation Status](https://readthedocs.org/projects/ohara/badge/?version=latest)](https://ohara.readthedocs.io/en/latest/?badge=latest)
[![Github Assets](https://img.shields.io/badge/download-assets-brightgreen.svg)](https://github.com/oharastream/ohara/releases)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

# Ohara

![pipeline-screenshot](images/pipeline_screenshot.jpg)

Ohara is an scalable streaming platform, that allows users to easily organized their input, output, and streaming applications with an clean and comprehensive GUI.

The streaming application ([StreamApp](https://ohara.readthedocs.io/en/latest/custom_streamapp.html)) contains the business logic defined by the user. By combining the applications with the default FTP, JDBC, HDFS, or [custom](https://ohara.readthedocs.io/en/latest/custom_connector.html) connectors, an user can construct a scalable pipeline with full control.

Ohara provides an architecture to scale, so the users can focus on their business logic and their main applications.

**Try this [Quick Start Guide](https://ohara.readthedocs.io/en/latest/user_guide.html#quick-start) to empower your business now!**

----

Ohara, a big data platform which is powered by [Apache Kafka](https://kafka.apache.org/) and [Docker](https://www.docker.com/), 
enables effective and quick ways of building application at streaming and scale.

* Super simple way to create following clusters:
  * [Apache Zookeeper](https://zookeeper.apache.org/) cluster
  * Apache Kafka - Broker cluster
  * Apache Kafka - Connect worker cluster
* Connect various storage: Build-in ready to use connectors for popular storage.
* Pipeline graph: A great user interface for your data pipeline design.
* StreamApp: Develop your own stream-processing logic for data transformation.
* Integrate with [Docker](https://www.docker.com/) & [Kubernetes](https://kubernetes.io/).


## [Ohara Documentation](https://ohara.readthedocs.io/en/latest)

## Contributing

Ohara is a completely open source project. If you're interested in contributing, you can start from the [contrubting guide](https://ohara.readthedocs.io/en/latest/contributing.html). Also, take a look at our [issue tracker](https://github.com/oharastream/ohara/issues), the [good first issues](https://github.com/oharastream/ohara/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22) would be a good place to start with.


## Ohara Team

- **Vito Jeng (vito@is-land.com.tw)** - leader
- **Jack Yang (jack@is-land.com.tw)** - committer
- **Chia-Ping Tsai (chia7712@is-land.com.tw)** - committer
- **Joshua_Lin (joshua@is-land.com.tw)** - committer
- **Sam Cho (sam@is-land.com.tw)** - committer
- **Chih-Chiang Yeh (harryyeh@is-land.com.tw)** - committer
- **Robert Ye (robertye@is-land.com.tw)** - committer


## License

Ohara is an open source project and available under the Apache 2.0 License.
