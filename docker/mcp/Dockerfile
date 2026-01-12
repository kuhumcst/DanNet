FROM python:3.13-slim

# Install uv
RUN pip install uv

WORKDIR /app

# Copy the MCP server code
COPY ./mcp/pyproject.toml /app/
COPY ./mcp/dannet_mcp_server.py /app/

# Install dependencies with uv
RUN uv sync

# Run the MCP server in HTTP mode, pointing to the dannet service
# The --base-url points to the internal docker network hostname
ENTRYPOINT ["uv", "run", "dannet_mcp_server.py", "--http", "--host", "0.0.0.0", "--base-url", "http://dannet:3456"]
