import json
import os.path
import random
import time

import requests
from loguru import logger
from jinja2 import Environment, FileSystemLoader

DOWNLOAD_PATH = "../downloads"
DATA_PATH = "../data"


def parse_content_file(course_name):
    contents = []
    article_source_path = f'{DATA_PATH}/{course_name}/content.txt'

    with open(article_source_path, 'r', encoding='utf-8') as article_file:
        for row in article_file.readlines():
            chapter_info = json.loads(row)['data']
            article_title = chapter_info.get('article_title')
            # article_content = chapter_info.get('article_content')
            # article_cover = chapter_info.get('article_cover')
            audio_download_url = chapter_info.get('audio_download_url')
            print(article_title, audio_download_url)
            contents.append(chapter_info)
    return contents


def generate_html(content, file_path):
    env = Environment(loader=FileSystemLoader('../data'))
    template = env.get_template("template.html")
    with open(file_path, "w+", encoding="utf-8") as output_file:
        html_content = template.render(data=content)
        output_file.write(html_content)


def get_content_from_har(har_file):
    contents = []
    with open(har_file, 'r', encoding='utf-8') as src_file:
        json_data = json.loads(src_file.read())
        print(len(json_data['log']['entries']))
        for entry in json_data['log']['entries']:
            if entry['request']['url'] == 'https://time.geekbang.org/serv/v1/article' and not entry['response']['content']['text'].startswith('ey'):
                contents.append(entry['response']['content']['text'])
    logger.info(f"获取文章数量:{len(contents)}")
    #     写入content.json
    with open(f"{article_source_path}/content.txt", "a+", encoding='utf-8') as src_data_file:
        src_data_file.seek(0)
        exist_contents = src_data_file.readlines()
        for content in contents:
            if not content.strip():
                continue
            # logger.info(json.loads(content)['data'])
            skip_flag = False
            for exist_content in exist_contents:
                if exist_content.strip() and json.loads(exist_content)['data']['article_content'] == json.loads(content)['data']['article_content']:
                    skip_flag = True
                    break
            if skip_flag:
                logger.warning(f"文章{json.loads(content)['data']['article_title']}数据已存在! ")
                continue
            src_data_file.write(content)
            src_data_file.write("\n")
    if len(contents) == 0:
        logger.error("获取文章失败!")
        exit(1)
    return contents


course_name = '深入剖析 Kubernetes'
article_output_path = f'{DOWNLOAD_PATH}/{course_name}/html'
if not os.path.exists(article_output_path):
    os.mkdir(article_output_path)

article_source_path = f'{DATA_PATH}/{course_name}'
if not os.path.exists(article_source_path):
    os.mkdir(article_source_path)

# 从har文件提取出contents
# har_file = 'c:/users/andydu/desktop/time.geekbang.org.har'
# contents = get_content_from_har(har_file)


contents = parse_content_file(course_name)
for chapter in contents:
    article_file_name = chapter['article_title'].replace(" ", "").replace("?", "？"). \
        replace("|", " ").replace("\"", "“").replace(":", "：").replace("<", "《").replace(">", "》"). \
        replace("/", "、").replace("*", "×").replace('\x08', '')
    chapter['audio_url'] = f"../{article_file_name}.mp3"
    generate_html(chapter, f"{article_output_path}/{article_file_name}.html")
