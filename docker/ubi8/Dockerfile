FROM quay.io/opsmxpublic/ubi8-jre-11:v1
MAINTAINER sig-platform@spinnaker.io
COPY fiat-web/build/install/fiat /opt/fiat
RUN yum -y install java-11-openjdk-headless.x86_64 wget vim net-tools curl nettle
RUN yum -y update
RUN adduser spinnaker
RUN mkdir -p /opt/fiat/plugins
RUN chmod -R 777 /opt/fiat/plugins/
RUN chown -R spinnaker:spinnaker /opt/
USER spinnaker
CMD ["/opt/fiat/bin/fiat"]
