FROM alpine:3.18.8

WORKDIR /test
RUN apk --update --no-cache add bash docker-cli
COPY ./run.sh /test/
CMD "/test/run.sh"
