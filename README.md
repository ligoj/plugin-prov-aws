# :link: Ligoj AWS plugin ![Maven Central](https://img.shields.io/maven-central/v/org.ligoj.plugin/plugin-prov-aws)

[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=org.ligoj.plugin%3Aplugin-prov-aws&metric=coverage)](https://sonarcloud.io/dashboard?id=org.ligoj.plugin%3Aplugin-prov-aws)
[![Quality Gate](https://sonarcloud.io/api/project_badges/measure?metric=alert_status&project=org.ligoj.plugin:plugin-prov-aws)](https://sonarcloud.io/dashboard/index/org.ligoj.plugin:plugin-prov-aws)
[![Codacy Badge](https://api.codacy.com/project/badge/Grade/7972cb9a10d54d119b8c434fef8d4013)](https://www.codacy.com/gh/ligoj/plugin-prov-aws?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=ligoj/plugin-prov-aws&amp;utm_campaign=Badge_Grade)
[![CodeFactor](https://www.codefactor.io/repository/github/ligoj/plugin-prov-aws/badge)](https://www.codefactor.io/repository/github/ligoj/plugin-prov-aws)
[![License](http://img.shields.io/:license-mit-blue.svg)](http://fabdouglas.mit-license.org/)

[Ligoj](https://github.com/ligoj/ligoj) AWS provisioning plugin, and extending [Provisioning plugin](https://github.com/ligoj/plugin-prov)
Provides the following features :
- Price fetching from AWS price API: Spot, Savings Plan, Reserved Instance and On Demand
- Region configuration. For now only one region at once
- Supported services : EC2, S3 (IA, Glacier,...), RDS (all engines), EBS(SSD, HDD, ...) and EFS
- Terraform port 
