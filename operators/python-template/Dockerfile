FROM python:3.7

RUN mkdir /app
ADD . /app/
WORKDIR /app
RUN pip install -r requirements.txt

RUN echo "Starting compilation"; (python -m compileall .) && echo "Finished compilation: succedeed" || (echo "Finished compilation: failed" && exit 1) 

CMD ["python", "./main.py"]

EXPOSE 8080