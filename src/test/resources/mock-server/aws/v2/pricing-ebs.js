/*
 * This file is intended for use only on aws.amazon.com. We do not guarantee its availability or accuracy.
 *
 * Copyright 2017 Amazon.com, Inc. or its affiliates. All rights reserved.
 */
callback({"vers":0.01,"config":{"currencies":["USD"],"rate":"perGB","regions":[
{"region":"eu-west-1","types":[

{"name":"ebsGPSSD","values":    [{"prices":{"USD":"0.10"},  "rate":"perGBmoProvStorage"}]},
{"name":"ebsPIOPSSSD","values": [{"prices":{"USD":"0.071"}, "rate":"perPIOPSreq"},
                                 {"prices":{"USD":"0.137"}, "rate":"perGBmoProvStorage"}]},
{"name":"ebsTOHDD","values":    [{"prices":{"USD":"0.045"}, "rate":"perGBmoProvStorage"}]},
{"name":"ebsColdHDD","values":  [{"prices":{"USD":"0.027"}, "rate":"perGBmoProvStorage"}]},
{"name":"ebsSnapsToS3","values":[{"prices":{"USD":"0.045"}, "rate":"perGBmoDataStored"}]}]},

{"region":"eu-west-2","types":[

{"name":"ebsGPSSD","values":    [{"prices":{"USD":"0.115"}, "rate":"perGBmoProvStorage"}]},
{"name":"ebsPIOPSSSD","values": [{"prices":{"USD":"0.144"}, "rate":"perGBmoProvStorage"},
                                 {"prices":{"USD":"0.075"}, "rate":"perPIOPSreq"}]},
{"name":"ebsTOHDD","values":    [{"prices":{"USD":"0.052"}, "rate":"perGBmoProvStorage"}]},
{"name":"ebsColdHDD","values":  [{"prices":{"USD":"0.028"}, "rate":"perGBmoProvStorage"}]},
{"name":"ebsSnapsToS3","values":[{"prices":{"USD":"0.052"}, "rate":"perGBmoDataStored"}]}]}]}});