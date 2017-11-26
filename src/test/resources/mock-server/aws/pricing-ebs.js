/*
 * This file is intended for use only on aws.amazon.com. We do not guarantee its availability or accuracy.
 *
 * Copyright 2017 Amazon.com, Inc. or its affiliates. All rights reserved.
 */
callback({"vers":0.01,"config":{"currencies":["USD"],"rate":"perGB","regions":[
{"region":"eu-west-1","types":[
{"name":"ebsGPSSD","values":    [{"prices":{"USD":"0.11"},  "rate":"perGBmoProvStorage"}]},
{"name":"ebsPIOPSSSD","values": [{"prices":{"USD":"0.138"}, "rate":"perGBmoProvStorage"},{"prices":{"USD":"0.072"},"rate":"perPIOPSreq"}]},
{"name":"ebsTOHDD","values":    [{"prices":{"USD":"0.05"},  "rate":"perGBmoProvStorage"}]},
{"name":"ebsColdHDD","values":  [{"prices":{"USD":"0.028"}, "rate":"perGBmoProvStorage"}]},
{"name":"ebsSnapsToS3","values":[{"prices":{"USD":"0.05"},  "rate":"perGBmoDataStored"}]}]},
{"region":"eu-west-2","types":[
{"name":"ebsGPSSD","values":    [{"prices":{"USD":"0.116"},"rate":"perGBmoProvStorage"}]},
{"name":"ebsPIOPSSSD","values": [{"prices":{"USD":"0.145"},"rate":"perGBmoProvStorage"},{"prices":{"USD":"0.076"},"rate":"perPIOPSreq"}]},
{"name":"ebsTOHDD","values":    [{"prices":{"USD":"0.053"},"rate":"perGBmoProvStorage"}]},
{"name":"ebsColdHDD","values":  [{"prices":{"USD":"0.029"},"rate":"perGBmoProvStorage"}]},
{"name":"ebsSnapsToS3","values":[{"prices":{"USD":"0.053"},"rate":"perGBmoDataStored"}]}]}]}});