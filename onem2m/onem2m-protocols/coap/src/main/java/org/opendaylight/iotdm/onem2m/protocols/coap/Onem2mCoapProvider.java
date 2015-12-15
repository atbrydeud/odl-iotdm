/*
 * Copyright (c) 2015 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.iotdm.onem2m.protocols.coap;

import org.eclipse.californium.core.CoapResource;
import org.eclipse.californium.core.CoapServer;
import org.eclipse.californium.core.coap.*;
import org.eclipse.californium.core.network.Exchange;
import org.eclipse.californium.core.server.resources.CoapExchange;
import org.eclipse.californium.core.server.resources.Resource;

import org.opendaylight.controller.sal.binding.api.BindingAwareBroker.ProviderContext;
import org.opendaylight.controller.sal.binding.api.BindingAwareProvider;
import org.opendaylight.iotdm.onem2m.client.Onem2mRequestPrimitiveClient;
import org.opendaylight.iotdm.onem2m.client.Onem2mRequestPrimitiveClientBuilder;
import org.opendaylight.iotdm.onem2m.core.Onem2m;
import org.opendaylight.iotdm.onem2m.core.Onem2mStats;
import org.opendaylight.iotdm.onem2m.core.rest.utils.ResponsePrimitive;
import org.opendaylight.iotdm.onem2m.notifier.Onem2mNotifierPlugin;
import org.opendaylight.iotdm.onem2m.notifier.Onem2mNotifierService;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.iotdm.onem2m.rev150105.Onem2mService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Onem2mCoapProvider extends CoapServer implements Onem2mNotifierPlugin, BindingAwareProvider, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Onem2mCoapProvider.class);
    protected Onem2mService onem2mService;

    @Override
    public void onSessionInitiated(ProviderContext session) {
        onem2mService = session.getRpcService(Onem2mService.class);
        Onem2mNotifierService.getInstance().pluginRegistration(this);

        start();
        LOG.info("Onem2mCoapProvider Session Initiated");
    }

    @Override
    public void close() throws Exception {
        LOG.info("Onem2mCoapProvider Closed");
    }

    /**
     * Intercept the coap URL query.
     */
    @Override
    public Resource createRoot() {
        return new RootResource();
    }

    private class RootResource extends CoapResource {
        public RootResource() {
            super("OpenDaylight OneM2M CoAP Server");
        }

        @Override
        public Resource getChild(String name) {
            return this;
        }

        /**
         * The handler for the CoAP request
         * @param exchange coap parameters
         */
        @Override
        public void handleRequest(final Exchange exchange) {
            CoAP.Code code = exchange.getRequest().getCode();
            CoapExchange coapExchange = new CoapExchange(exchange, this);
            OptionSet options = coapExchange.advanced().getRequest().getOptions();

            // onem2m needs type = CON, ACK, RST - see binding spec
            //if (exchange.getRequest().getType() != CoAP.Type.CON) {
            //   coapExchange.respond(CoAP.ResponseCode.BAD_REQUEST, "Invalid CoAP type:" + exchange.getRequest().getType());
            //   return;
            //}
            Onem2mRequestPrimitiveClientBuilder clientBuilder = new Onem2mRequestPrimitiveClientBuilder();
            String optionValue;

            clientBuilder.setProtocol(Onem2m.Protocol.COAP);

            Onem2mStats.getInstance().endpointInc(coapExchange.getSourceAddress().toString());
            Onem2mStats.getInstance().inc(Onem2mStats.COAP_REQUESTS);

            if (options.getContentFormat() == MediaTypeRegistry.APPLICATION_JSON) {
                clientBuilder.setContentFormat(Onem2m.ContentFormat.JSON);
            } else if (options.getContentFormat() == MediaTypeRegistry.APPLICATION_XML) {
                clientBuilder.setContentFormat(Onem2m.ContentFormat.XML);
            } else {
                clientBuilder.setContentFormat(Onem2m.ContentFormat.JSON);

                //coapExchange.respond(CoAP.ResponseCode.NOT_ACCEPTABLE, "Unknown media type: " +
                //        options.getContentFormat());
                //Onem2mStats.getInstance().inc(Onem2mStats.COAP_REQUESTS_ERROR);

                //return;
            }

            clientBuilder.setTo(options.getURIPathString()); // To/TargetURI
            // M3 clientBuilder.setTo(options.getUriPathString()); // To/TargetURI // M3

            processOptions(options, clientBuilder); // pull options out of coap header fields

            // according to the spec, the uri query string can contain in short form, the
            // resourceType, responseType, result persistence,  Delivery Aggregation, Result Content,
            Boolean resourceTypePresent = clientBuilder.parseQueryStringIntoPrimitives(options.getURIQueryString());
            // M3 Boolean resourceTypePresent = clientBuilder.parseQueryStringIntoPrimitives(options.getUriQueryString());
            if (resourceTypePresent && code != CoAP.Code.POST) {
                coapExchange.respond(CoAP.ResponseCode.BAD_REQUEST, "Specifying resource type not permitted.");
                Onem2mStats.getInstance().inc(Onem2mStats.COAP_REQUESTS_ERROR);
                return;
            }

            // take the entire payload text and put it in the CONTENT field; it is the representation of the resource
            String cn = coapExchange.getRequestText().trim();
            if (cn != null && !cn.contentEquals("")) {
                clientBuilder.setPrimitiveContent(cn);
            }

            switch (code) {
                case GET:
                    clientBuilder.setOperationRetrieve();
                    Onem2mStats.getInstance().inc(Onem2mStats.COAP_REQUESTS_RETRIEVE);
                    break;

                case POST:
                    if (resourceTypePresent) {
                        clientBuilder.setOperationCreate();
                        Onem2mStats.getInstance().inc(Onem2mStats.COAP_REQUESTS_CREATE);
                    } else {
                        clientBuilder.setOperationNotify();
                        Onem2mStats.getInstance().inc(Onem2mStats.COAP_REQUESTS_NOTIFY);
                    }
                    break;

                case PUT:
                    clientBuilder.setOperationUpdate();
                    Onem2mStats.getInstance().inc(Onem2mStats.COAP_REQUESTS_UPDATE);
                    break;

                case DELETE:
                    clientBuilder.setOperationDelete();
                    Onem2mStats.getInstance().inc(Onem2mStats.COAP_REQUESTS_DELETE);
                    break;

                default:
                    coapExchange.respond(CoAP.ResponseCode.BAD_REQUEST, "Unknown code: " + code);
                    Onem2mStats.getInstance().inc(Onem2mStats.COAP_REQUESTS_ERROR);
                    return;
            }

            Onem2mRequestPrimitiveClient onem2mRequest = clientBuilder.build();
            ResponsePrimitive onem2mResponse = Onem2m.serviceOnenm2mRequest(onem2mRequest, onem2mService);

            // now place the fields from the onem2m result response back in the coap fields, and send
            sendCoapResponseFromOnem2mResponse(coapExchange, options, onem2mResponse);

        }

        private void sendCoapResponseFromOnem2mResponse(CoapExchange exchange,
                                                        OptionSet options,
                                                        ResponsePrimitive onem2mResponse) {

            // the content is already in the required format ...
            String content = onem2mResponse.getPrimitive(ResponsePrimitive.CONTENT);
            String rscString = onem2mResponse.getPrimitive(ResponsePrimitive.RESPONSE_STATUS_CODE);
            CoAP.ResponseCode coapRSC = mapCoreResponseToCoapResponse(rscString);
            // return the request id in the return option
            String rqi = onem2mResponse.getPrimitive(ResponsePrimitive.REQUEST_IDENTIFIER);
            if (rqi != null) {
                options.addOption(new Option(Onem2m.CoapOption.ONEM2M_RQI, rqi));
            }
            // put the onem2m response code into the RSC option and return it too
            options.addOption(new Option(Onem2m.CoapOption.ONEM2M_RSC, Integer.parseInt(rscString)));
            if (content != null) {
                exchange.respond(coapRSC, content);
            } else {
                exchange.respond(coapRSC);
            }
            if (rscString.charAt(0) =='2') {
                Onem2mStats.getInstance().inc(Onem2mStats.COAP_REQUESTS_OK);
            } else {
                Onem2mStats.getInstance().inc(Onem2mStats.COAP_REQUESTS_ERROR);
            }

        }

        /**
         * For each option, find the onem2m options and set the appropriate fields
         * @param options
         * @param clientBuilder
         * @return
         */
        private void processOptions(OptionSet options, Onem2mRequestPrimitiveClientBuilder clientBuilder) {
            for (Option opt : options.asSortedList()) {

                switch (opt.getNumber()) {
                    case Onem2m.CoapOption.ONEM2M_FR:
                        clientBuilder.setFrom(opt.getStringValue());
                        break;
                    case Onem2m.CoapOption.ONEM2M_RQI:
                        clientBuilder.setRequestIdentifier(opt.getStringValue());
                        break;
                    case Onem2m.CoapOption.ONEM2M_NM:
                        clientBuilder.setName(opt.getStringValue());
                        break;
                    case Onem2m.CoapOption.ONEM2M_OT:
                        clientBuilder.setOriginatingTimestamp(opt.getStringValue());
                        break;
                    case Onem2m.CoapOption.ONEM2M_RQET:
                        clientBuilder.setRequestExpirationTimestamp(opt.getStringValue());
                        break;
                    case Onem2m.CoapOption.ONEM2M_RSET:
                        clientBuilder.setResultExpirationTimestamp(opt.getStringValue());
                        break;
                    case Onem2m.CoapOption.ONEM2M_OET:
                        clientBuilder.setOperationExecutionTime(opt.getStringValue());
                        break;
                    case Onem2m.CoapOption.ONEM2M_EC:
                        //clientBuilder.setEventCategory(opt.getIntegerValue());
                        break;
                    case Onem2m.CoapOption.ONEM2M_GID:
                        clientBuilder.setGroupRequestIdentifier(opt.getStringValue());
                        break;
                }
            }
        }

        private CoAP.ResponseCode mapCoreResponseToCoapResponse(String rscString) {

            switch (rscString) {
                case Onem2m.ResponseStatusCode.OK:
                    return CoAP.ResponseCode.CONTENT;
                case Onem2m.ResponseStatusCode.CREATED:
                    return CoAP.ResponseCode.CREATED;
                case Onem2m.ResponseStatusCode.CHANGED:
                    return CoAP.ResponseCode.CHANGED;
                case Onem2m.ResponseStatusCode.DELETED:
                    return CoAP.ResponseCode.DELETED;

                case Onem2m.ResponseStatusCode.NOT_FOUND:
                    return CoAP.ResponseCode.NOT_FOUND;
                case Onem2m.ResponseStatusCode.OPERATION_NOT_ALLOWED:
                    return CoAP.ResponseCode.METHOD_NOT_ALLOWED;
                case Onem2m.ResponseStatusCode.CONTENTS_UNACCEPTABLE:
                    return CoAP.ResponseCode.BAD_REQUEST;
                case Onem2m.ResponseStatusCode.CONFLICT:
                    return CoAP.ResponseCode.FORBIDDEN;

                case Onem2m.ResponseStatusCode.INTERNAL_SERVER_ERROR:
                    return CoAP.ResponseCode.INTERNAL_SERVER_ERROR;
                case Onem2m.ResponseStatusCode.NOT_IMPLEMENTED:
                    return CoAP.ResponseCode.NOT_IMPLEMENTED;
                case Onem2m.ResponseStatusCode.ALREADY_EXISTS:
                    return CoAP.ResponseCode.BAD_REQUEST;
                case Onem2m.ResponseStatusCode.TARGET_NOT_SUBSCRIBABLE:
                    return CoAP.ResponseCode.FORBIDDEN;
                case Onem2m.ResponseStatusCode.NON_BLOCKING_REQUEST_NOT_SUPPORTED:
                    return CoAP.ResponseCode.INTERNAL_SERVER_ERROR;

                case Onem2m.ResponseStatusCode.INVALID_ARGUMENTS:
                    return CoAP.ResponseCode.BAD_REQUEST;
                case Onem2m.ResponseStatusCode.INSUFFICIENT_ARGUMENTS:
                    return CoAP.ResponseCode.BAD_REQUEST;
            }
            return CoAP.ResponseCode.BAD_REQUEST;
        }
    }

    // implement the Onem2mNotifierPlugin interface
    @Override
    public String getNotifierPluginName() {
        return "coap";
    }

    @Override
    public void sendNotification(String url, String payload) {
        Request request = Request.newPost();
        request.setURI(url);
        request.setPayload(payload);
        request.send();
        LOG.debug("CoAP: Send notification uri: {}, payload: {}:", url, payload);
    }
}
