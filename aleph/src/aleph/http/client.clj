;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.http.client
  (:use
    [aleph netty formats]
    [aleph.http core utils]
    [aleph.core channel pipeline]
    [clojure.contrib.json])
  (:require
    [clj-http.client :as client])
  (:import
    [org.jboss.netty.handler.codec.http
     HttpRequest
     HttpResponse
     HttpResponseStatus
     HttpClientCodec
     HttpChunk
     DefaultHttpChunk
     HttpContentDecompressor
     DefaultHttpRequest
     HttpMessage
     HttpMethod
     HttpHeaders
     HttpVersion]
    [org.jboss.netty.channel
     Channel
     ExceptionEvent]
    [java.net
     URI]))

;;;

(defn transform-aleph-request [scheme ^String host ^Integer port request]
  (let [request (wrap-client-request request)
	uri (URI. scheme nil host port (:uri request) (:query-string request) (:fragment request))
        req (DefaultHttpRequest.
	      HttpVersion/HTTP_1_1
	      (request-methods (:request-method request))
	      (str
		(when-not (= \/ (-> uri .getPath first))
		  "/")
		(.getPath uri)
		(when-not (empty? (.getQuery uri))
		  "?")
		(.getQuery uri)))]
    (.setHeader req "host" (str host ":" port))
    (.setHeader req "accept-encoding" "gzip")
    (.setHeader req "connection" "keep-alive")
    (doseq [[k v-or-vals] (:headers request)]
      (when-not (nil? v-or-vals)
	(if (string? v-or-vals)
	  (.addHeader req (to-str k) v-or-vals)
	  (doseq [val v-or-vals]
	    (.addHeader req (to-str k) val)))))
    (when-let [body (:body request)]
      (if (channel? body)
	(.setHeader req "transfer-encoding" "chunked")
	(.setContent req (transform-aleph-body body (:headers request)))))
    req))

;;;

(defn read-streaming-response [headers in out]
  (run-pipeline in
    :error-handler (fn [_ ex] (.printStackTrace ex))
    receive-from-channel
    (fn [^HttpChunk response]
      (let [body (transform-netty-body (.getContent response) headers)]
	(if (.isLast response)
	  (enqueue-and-close out body)
	  (do
	    (enqueue out body)
	    (restart)))))))

(defn read-responses [netty-channel in out]
  (run-pipeline in
    :error-handler (fn [_ ex] (.printStackTrace ex))
    receive-from-channel
    (fn [^HttpResponse response]
      (let [chunked? (.isChunked response)
	    headers (netty-headers response)
	    response (transform-netty-response response headers)]
	(if-not chunked?
	  (enqueue out response)
	  (let [body (:body response)
		stream (channel)
		close (single-shot-channel)
		response (assoc response :body (splice stream close))]
	    (receive close
	      (fn [_] (.close netty-channel)))
	    (when body
	      (enqueue stream body))
	    (enqueue out response)
	    (read-streaming-response headers in stream)))))
    (fn [_]
      (restart))))

;;;

(defn read-streaming-request [in out headers]
  (run-pipeline in
    :error-handler (fn [_ ex] (.printStackTrace ex))
    receive-from-channel
    (fn [chunk]
      (enqueue out (DefaultHttpChunk. (transform-aleph-body chunk headers)))
      (if (closed? in)
	(enqueue out HttpChunk/LAST_CHUNK)
	(restart)))))

(defn read-requests [in out options]
  (run-pipeline in
    :error-handler (fn [_ ex] (.printStackTrace ex))
    receive-from-channel
    (fn [request]
      (enqueue out
	(transform-aleph-request
	  (:scheme options)
	  (:server-name options)
	  (:server-port options)
	  request))
      (when (channel? (:body request))
	(read-streaming-request (:body request) out (:headers request))))
    (fn [_]
      (restart))))

;;;

(defn create-pipeline [client close? options]
  (let [responses (channel)
	init? (atom false)]
    (create-netty-pipeline
      :codec (HttpClientCodec.)
      :inflater (HttpContentDecompressor.)
      ;;:upstream-decoder (upstream-stage (fn [x] (println "client request\n" x) x))
      ;;:downstream-decoder (downstream-stage (fn [x] (println "client response\n" x) x))
      :upstream-error (upstream-stage error-stage-handler)
      :response (message-stage
		  (fn [netty-channel rsp]
		    (when (compare-and-set! init? false true)
		      (read-responses netty-channel responses client))
		    (enqueue responses rsp)))
      :downstream-error (downstream-stage error-stage-handler))))

;;;

(defprotocol HttpClient
  (create-request-channel [c])
  (close-http-client [c]))

(defn raw-http-client
  "Create an HTTP client."
  [options]
  (let [options (-> options
		  split-url
		  (update-in [:server-port] #(or % 80)))
	requests (channel)
	client (create-client
		 #(create-pipeline
		    %
		    (or (:close? options) (constantly false))
		    options)
		 identity
		 options)]
    (run-pipeline client
      #(read-requests requests % options))
    (reify HttpClient
      (create-request-channel [_]
	(run-pipeline client
	  #(splice % requests)))
      (close-http-client [_]
	(enqueue-and-close (-> client run-pipeline wait-for-pipeline)
	  nil)))))

(defn http-request
  ([request]
     (http-request
       (raw-http-client request)
       request))
  ([client request]
     (run-pipeline client
       create-request-channel
       (fn [ch]
	 (enqueue ch request)
	 ch))))

