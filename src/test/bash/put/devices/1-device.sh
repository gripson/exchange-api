# Update device 1 as device
if [[ $1 == "-raw" ]]; then parse=cat; else parse="jq -r ."; fi
curl -# -w "%{http_code}" -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic 1:abc123" -d '{
  "token": "abc123",
  "name": "rpi1-updated",
  "registeredMicroservices": [
    {
      "url": "https://bluehorizon.network/documentation/sdr-device-api",
      "numAgreements": 1,
      "policy": "{json policy for rpi1 sdr}",
      "properties": [
        {
          "name": "arch",
          "value": "arm",
          "propType": "string",
          "op": "in"
        },
        {
          "name": "memory",
          "value": "300",
          "propType": "int",
          "op": ">="
        },
        {
          "name": "version",
          "value": "1.0",
          "propType": "version",
          "op": "in"
        },
        {
          "name": "agreementProtocols",
          "value": "ExchangeManualTest",
          "propType": "list",
          "op": "in"
        },
        {
          "name": "dataVerification",
          "value": "true",
          "propType": "boolean",
          "op": "="
        }
      ]
    },
    {
      "url": "https://bluehorizon.network/documentation/netspeed-device-api",
      "numAgreements": 1,
      "policy": "{json policy for rpi1 netspeed}",
      "properties": [
        {
          "name": "arch",
          "value": "arm",
          "propType": "string",
          "op": "in"
        },
        {
          "name": "version",
          "value": "1.0.0",
          "propType": "version",
          "op": "in"
        }
      ]
    }
  ],
  "msgEndPoint": "whisper-id",
  "softwareVersions": {"horizon": "3.2.1"}
}' $EXCHANGE_URL_ROOT/v1/devices/1 | $parse