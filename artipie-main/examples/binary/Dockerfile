FROM alpine:3.19.3
WORKDIR /test
RUN apk --update --no-cache add curl bash
COPY run.sh run.sh
CMD /test/run.sh
