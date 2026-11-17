FROM python:3.12-slim
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/* \
    && pip install --no-cache-dir gunicorn==23.0.0 \
    && mkdir -p /var/lib/bilingualreader/asr && chown -R 65534:65534 /var/lib/bilingualreader
COPY gateway.py asr.py dictionary_lookup.py /app/
USER 65534:65534
EXPOSE 8000
# One worker: request limits and concurrency controls are intentionally process-local.
CMD ["gunicorn", "--bind", "0.0.0.0:8000", "--workers", "1", "--threads", "4", "--timeout", "70", "--error-logfile", "-", "gateway:application"]
