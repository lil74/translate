FROM python:3.12-slim
WORKDIR /app
RUN pip install --no-cache-dir gunicorn==23.0.0
COPY gateway.py /app/gateway.py
USER 65534:65534
EXPOSE 8000
# One worker: request limits and concurrency controls are intentionally process-local.
CMD ["gunicorn", "--bind", "0.0.0.0:8000", "--workers", "1", "--threads", "4", "--timeout", "35", "--error-logfile", "-", "gateway:application"]
