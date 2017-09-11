# :link: Ligoj AWS plugin [![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.ligoj.plugin/plugin-prov-aws/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.ligoj.plugin/plugin-prov-aws)

[![Build Status](https://travis-ci.org/ligoj/plugin-prov-aws.svg?branch=master)](https://travis-ci.org/ligoj/plugin-prov-aws)
[![Build Status](https://circleci.com/gh/ligoj/plugin-prov-aws.svg?style=svg)](https://circleci.com/gh/ligoj/plugin-prov-aws)
[![Build Status](https://codeship.com/projects/a8c64bc0-05c9-0135-5add-32bab775782c/status?branch=master)](https://codeship.com/projects/213622)
[![Build Status](https://semaphoreci.com/api/v1/ligoj/plugin-prov-aws/branches/master/shields_badge.svg)](https://semaphoreci.com/ligoj/plugin-prov-aws)
[![Build Status](https://ci.appveyor.com/api/projects/status/5926fmf0p5qp9j16/branch/master?svg=true)](https://ci.appveyor.com/project/ligoj/plugin-prov-aws/branch/master)
[![Coverage Status](https://coveralls.io/repos/github/ligoj/plugin-prov-aws/badge.svg?branch=master)](https://coveralls.io/github/ligoj/plugin-prov-aws?branch=master)
[![Dependency Status](https://www.versioneye.com/user/projects/58caeda8dcaf9e0041b5b978/badge.svg?style=flat)](https://www.versioneye.com/user/projects/58caeda8dcaf9e0041b5b978)
[![Quality Gate](https://sonarqube.com/api/badges/gate?key=org.ligoj.plugin:plugin-prov-aws)](https://sonarqube.com/dashboard/index/org.ligoj.plugin:plugin-prov-aws)
[![Sourcegraph Badge](https://sourcegraph.com/github.com/ligoj/plugin-prov-aws/-/badge.svg)](https://sourcegraph.com/github.com/ligoj/plugin-prov-aws?badge)
[![Codacy Badge](https://api.codacy.com/project/badge/Grade/7972cb9a10d54d119b8c434fef8d4013)](https://www.codacy.com/app/ligoj/plugin-prov-aws?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=ligoj/plugin-prov-aws&amp;utm_campaign=Badge_Grade)
[![CodeFactor](https://www.codefactor.io/repository/github/ligoj/plugin-prov-aws/badge)](https://www.codefactor.io/repository/github/ligoj/plugin-prov-aws)
[![License](http://img.shields.io/:license-mit-blue.svg)](http://gus.mit-license.org/)

[Ligoj](https://github.com/ligoj/ligoj) AWS provisioning plugin, and extending [Provisioning plugin](https://github.com/ligoj/plugin-prov)
Provides the following features :
- Price fetching from AWS site, including Spot
- Region configuration. For now only one region at once
- Supported services : EC2, S3 (IA, Glacier,..) and EBS(SSD, HDD, ...)
- Terraform port 
